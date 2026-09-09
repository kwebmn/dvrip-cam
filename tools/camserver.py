#!/usr/bin/env python3
import http.server, socketserver, json, socket, struct, hashlib, threading
import subprocess, os, urllib.parse, datetime, time, re

# Конфиг из окружения: CAM_HOST, CAM_PORT, CAM_PASS
CAM=os.environ.get("CAM_HOST","192.168.1.10"); CPORT=int(os.environ.get("CAM_PORT","34567")); PW=os.environ.get("CAM_PASS","")
BASE=os.path.dirname(os.path.abspath(__file__))
CACHE=os.path.join(BASE,"cache"); os.makedirs(CACHE,exist_ok=True)
LOCK=threading.Lock()

def sh(pw):
    m=hashlib.md5(pw.encode()).digest()
    c="0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    return "".join(c[(m[i*2]+m[i*2+1])%62] for i in range(8))
def _ra(s,n):
    b=b""
    while len(b)<n:
        x=s.recv(n-len(b))
        if not x: break
        b+=x
    return b
def _send(s,se,sq,mid,o):
    body=json.dumps(o).encode()+b"\x0a\x00"
    s.sendall(struct.pack("<BBHIIBBHI",0xff,0,0,se,sq,0,0,mid,len(body))+body)
def _pkt(s):
    hd=_ra(s,20)
    if len(hd)<20: return None,None,b""
    mid=struct.unpack("<H",hd[14:16])[0]; ln=struct.unpack("<I",hd[16:20])[0]
    return mid,ln,(_ra(s,ln) if ln else b"")
def login(timeout=5):
    s=socket.create_connection((CAM,CPORT),timeout); s.settimeout(8)
    _send(s,0,0,1000,{"EncryptType":"MD5","LoginType":"DVRIP-Web","PassWord":sh(PW),"UserName":"admin"})
    _,_,bd=_pkt(s); js=json.loads(bd.split(b"\x00")[0].decode("utf-8","replace"))
    if js.get("Ret")!=100: s.close(); raise RuntimeError("login Ret=%s"%js.get("Ret"))
    return s,int(js["SessionID"],16),js["SessionID"]
def query(s,sid,H,seq,beg,end):
    q={"Name":"OPFileQuery","SessionID":H,"OPFileQuery":{"BeginTime":beg,"EndTime":end,"Channel":0,
       "DriverTypeMask":"0x0000FFFF","Event":"*","StreamType":"0x00000000","Type":"h264"}}
    _send(s,sid,seq,1440,q); _,_,bd=_pkt(s)
    try: r=json.loads(bd.split(b"\x00")[0].decode("utf-8","replace"))
    except: return []
    f=r.get("OPFileQuery"); return f if isinstance(f,list) else []

def list_range(begin_date,end_date):
    """iterate days, per day full then 6h windows if capped; return sorted files."""
    d0=datetime.datetime.strptime(begin_date,"%Y-%m-%d").date()
    d1=datetime.datetime.strptime(end_date,"%Y-%m-%d").date()
    if (d1-d0).days>62: d1=d0+datetime.timedelta(days=62)
    wins=[("00:00:00","05:59:59"),("06:00:00","11:59:59"),("12:00:00","17:59:59"),("18:00:00","23:59:59")]
    out={}
    with LOCK:
        s,sid,H=login(); seq=1
        try:
            day=d0
            while day<=d1:
                ds=day.strftime("%Y-%m-%d")
                full=query(s,sid,H,seq,ds+" 00:00:00",ds+" 23:59:59"); seq+=1
                if len(full)>=64:
                    for a,b in wins:
                        for f in query(s,sid,H,seq,ds+" "+a,ds+" "+b): out[f.get("FileName","")]=f
                        seq+=1
                else:
                    for f in full: out[f.get("FileName","")]=f
                day+=datetime.timedelta(days=1)
        finally: s.close()
    files=[]
    for nm,f in out.items():
        b=f.get("BeginTime",""); e=f.get("EndTime","")
        files.append({"name":nm,"beg":b,"end":e,
            "type":"A" if "[A]" in nm else ("R" if "[R]" in nm else "?")})
    files.sort(key=lambda x:x["beg"])
    return files

def clip_mp4(name,beg,end):
    key=re.sub(r'[^0-9A-Za-z]+','_',name)+".mp4"
    path=os.path.join(CACHE,key)
    if os.path.exists(path) and os.path.getsize(path)>1000: return path
    with LOCK:
        s,sid,H=login()
        try:
            par={"FileName":name,"PlayMode":"ByName","StreamType":0,"Value":0,"TransMode":"TCP"}
            _send(s,sid,1,1424,{"Name":"OPPlayBack","SessionID":H,"OPPlayBack":{"Action":"Claim","StartTime":beg,"EndTime":end,"Parameter":par}}); _pkt(s)
            _send(s,sid,2,1420,{"Name":"OPPlayBack","SessionID":H,"OPPlayBack":{"Action":"DownloadStart","StartTime":beg,"EndTime":end,"Parameter":par}})
            buf=bytearray(); t0=time.time(); s.settimeout(12)
            while time.time()-t0<120:
                try: mid,ln,bd=_pkt(s)
                except: break
                if mid is None or ln==0: break
                if bd[:1]!=b"{": buf+=bd
        finally: s.close()
    data=bytes(buf); sc=b"\x00\x00\x00\x01"; idx=[]; i=data.find(sc)
    while i>=0: idx.append(i); i=data.find(sc,i+4)
    out=bytearray()
    for k,pos in enumerate(idx):
        e=idx[k+1] if k+1<len(idx) else len(data); out+=data[pos:e]
    raw=path+".h264"; open(raw,"wb").write(out)
    # fps from duration
    try:
        db=datetime.datetime.strptime(beg,"%Y-%m-%d %H:%M:%S"); de=datetime.datetime.strptime(end,"%Y-%m-%d %H:%M:%S")
        secs=max(1,(de-db).total_seconds())
    except: secs=len(idx)/7.0
    vframes=sum(1 for p in idx if (data[p+4]&0x1f) in (1,5))
    fps=max(1,min(30,round(vframes/secs))) if secs>0 else 7
    subprocess.run(["ffmpeg","-y","-loglevel","error","-r",str(fps),"-f","h264","-i",raw,
                    "-c","copy","-movflags","+faststart",path],check=False)
    try: os.remove(raw)
    except: pass
    return path

PAGE=r"""<!doctype html><html><head><meta charset=utf-8><title>Камера</title>
<meta name=viewport content="width=device-width,initial-scale=1">
<style>
:root{color-scheme:dark}body{margin:0;font:14px system-ui,Segoe UI,Roboto,sans-serif;background:#111;color:#eee}
header{background:#1b1b1f;padding:10px 14px;display:flex;gap:10px;align-items:center;border-bottom:1px solid #333}
header b{font-size:16px}.tabs{margin-left:auto;display:flex;gap:6px}
.tab{padding:6px 14px;background:#2a2a30;border-radius:6px;cursor:pointer}.tab.on{background:#3b82f6}
.wrap{padding:14px;max-width:1100px;margin:auto}
video,iframe{width:100%;background:#000;border-radius:8px;border:0}
.row{display:flex;gap:8px;flex-wrap:wrap;margin:10px 0}
button{background:#2a2a30;color:#eee;border:1px solid #444;border-radius:6px;padding:7px 12px;cursor:pointer}
button:hover{background:#37373f}button.p{background:#3b82f6;border-color:#3b82f6}
#list{max-height:60vh;overflow:auto;border:1px solid #333;border-radius:8px}
.it{padding:8px 12px;border-bottom:1px solid #262626;cursor:pointer;display:flex;gap:10px;align-items:center}
.it:hover{background:#1e1e24}.A{color:#f59e0b}.R{color:#34d399}
.badge{font-size:11px;padding:1px 6px;border-radius:4px;border:1px solid #444}
.muted{color:#888}.hide{display:none}#msg{padding:8px 12px;color:#f59e0b}
select,input{background:#2a2a30;color:#eee;border:1px solid #444;border-radius:6px;padding:6px}
</style></head><body>
<header><b>📷 Камера</b>
<span id=st class=muted>…</span>
<div class=tabs><div class="tab on" data-t=live>Live</div><div class="tab" data-t=arch>Архив (SD)</div></div>
</header>
<div class=wrap>
 <div id=live>
   <div class=row><button onclick="setsrc('cam')" class=p>1080p</button>
     <button onclick="setsrc('cam_720')">720p</button>
     <button onclick="setsrc('cam_sub')">Лёгкий</button>
     <span class=muted>Если чёрный экран — разбуди камеру (помаши рукой перед ней).</span></div>
   <iframe id=liveframe allow="autoplay" height=560 src="http://localhost:1984/webrtc.html?src=cam_sub"></iframe>
 </div>
 <div id=arch class=hide>
   <div class=row>
     <button onclick="load('2026-09-07','2026-09-09')" class=p>Сент 2026</button>
     <button onclick="load('2025-06-23','2025-06-24')">Июнь 2025</button>
     <button onclick="load('2024-06-21','2024-06-21')">Июнь 2024</button>
     <button onclick="load('2023-09-29','2023-09-30')">Сент 2023</button>
     <input type=date id=d0><input type=date id=d1>
     <button onclick="load(d0.value,d1.value)">Показать</button>
   </div>
   <video id=player controls height=480></video>
   <div class=row><button onclick="dlfull()">⤓ Скачать целиком (точная перемотка)</button></div>
   <div id=msg></div>
   <div id=list></div>
 </div>
</div>
<script>
const tabs=document.querySelectorAll('.tab');
tabs.forEach(t=>t.onclick=()=>{tabs.forEach(x=>x.classList.remove('on'));t.classList.add('on');
  live.classList.toggle('hide',t.dataset.t!='live');arch.classList.toggle('hide',t.dataset.t!='arch');});
function setsrc(s){liveframe.src="http://localhost:1984/webrtc.html?src="+s;}
async function status(){try{let r=await fetch('/api/status');let j=await r.json();
  st.textContent=j.awake?'● в сети '+j.ip:'○ спит (помаши рукой)';st.style.color=j.awake?'#34d399':'#f59e0b';}catch(e){st.textContent='нет сервера'}}
setInterval(status,5000);status();
async function load(a,b){if(!a||!b)return;msg.textContent='Загружаю список…';list.innerHTML='';
  try{let r=await fetch('/api/list?begin='+a+'&end='+b);let j=await r.json();
    if(j.error){msg.textContent=j.error;return}
    msg.textContent=j.files.length+' записей ([A]=движение, [R]=обычная)';
    list.innerHTML=j.files.map(f=>`<div class=it onclick='play(${JSON.stringify(f).replace(/'/g,"&#39;")})'>
      <span class="badge ${f.type}">${f.type}</span><b>${f.beg}</b>
      <span class=muted>→ ${f.end.slice(11)}</span></div>`).join('');
  }catch(e){msg.textContent='Ошибка: '+e}}
let cur=null;
function q(f,ep){return ep+'?name='+encodeURIComponent(f.name)+'&beg='+encodeURIComponent(f.beg)+'&end='+encodeURIComponent(f.end);}
async function play(f){cur=f;msg.textContent='▶ запускаю '+f.beg+' …';
  player.src=q(f,'/api/stream');player.load();player.play().catch(()=>{});
  player.onplaying=()=>msg.textContent='▶ '+f.beg+'  ·  для точной перемотки нажми «Скачать целиком»';
  player.onerror=()=>msg.textContent='Не удалось (камера спит? разбуди и повтори)';}
function dlfull(){if(!cur)return;msg.textContent='Скачиваю целиком (для перемотки)…';
  player.src=q(cur,'/api/clip');player.load();player.play().catch(()=>{});
  player.onplaying=()=>msg.textContent='▶ '+cur.beg+' (скачано целиком, перемотка доступна)';}
</script></body></html>"""

class H(http.server.BaseHTTPRequestHandler):
    def log_message(self,*a): pass
    def _json(self,o,code=200):
        b=json.dumps(o).encode(); self.send_response(code)
        self.send_header("Content-Type","application/json"); self.send_header("Content-Length",str(len(b)))
        self.end_headers(); self.wfile.write(b)
    def do_GET(self):
        u=urllib.parse.urlparse(self.path); q=urllib.parse.parse_qs(u.query)
        if u.path=="/":
            b=PAGE.encode(); self.send_response(200)
            self.send_header("Content-Type","text/html; charset=utf-8"); self.send_header("Content-Length",str(len(b)))
            self.end_headers(); self.wfile.write(b); return
        if u.path=="/api/status":
            try:
                s,_,_=login(timeout=3); s.close(); self._json({"awake":True,"ip":CAM})
            except Exception as e: self._json({"awake":False,"err":str(e)[:40]})
            return
        if u.path=="/api/list":
            try:
                files=list_range(q.get("begin",[""])[0],q.get("end",[""])[0])
                self._json({"files":files})
            except Exception as e: self._json({"error":"Камера недоступна (спит?). Разбуди и повтори. [%s]"%str(e)[:40]})
            return
        if u.path=="/api/stream":
            name=q.get("name",[""])[0]; beg=q.get("beg",[""])[0]; end=q.get("end",[""])[0]
            self.send_response(200)
            self.send_header("Content-Type","video/mp4"); self.send_header("Cache-Control","no-store")
            self.end_headers()
            ff=subprocess.Popen(["ffmpeg","-hide_banner","-loglevel","error","-fflags","+genpts",
                "-r","7","-f","h264","-i","pipe:0","-c","copy",
                "-movflags","frag_keyframe+empty_moov+default_base_moof","-f","mp4","pipe:1"],
                stdin=subprocess.PIPE,stdout=subprocess.PIPE)
            stop={"v":False}
            def feed():
                try:
                    with LOCK:
                        s,sid,H=login()
                        try:
                            par={"FileName":name,"PlayMode":"ByName","StreamType":0,"Value":0,"TransMode":"TCP"}
                            _send(s,sid,1,1424,{"Name":"OPPlayBack","SessionID":H,"OPPlayBack":{"Action":"Claim","StartTime":beg,"EndTime":end,"Parameter":par}}); _pkt(s)
                            _send(s,sid,2,1420,{"Name":"OPPlayBack","SessionID":H,"OPPlayBack":{"Action":"DownloadStart","StartTime":beg,"EndTime":end,"Parameter":par}})
                            s.settimeout(12); t0=time.time()
                            while time.time()-t0<180 and not stop["v"]:
                                try: mid,ln,bd=_pkt(s)
                                except: break
                                if mid is None or ln==0: break
                                if bd[:1]!=b"{":
                                    try: ff.stdin.write(bd)
                                    except: break
                        finally: s.close()
                except: pass
                try: ff.stdin.close()
                except: pass
            threading.Thread(target=feed,daemon=True).start()
            try:
                while True:
                    chunk=ff.stdout.read(65536)
                    if not chunk: break
                    self.wfile.write(chunk)
            except: pass
            stop["v"]=True
            for fn in (getattr(ff.stdout,"close",None),getattr(ff,"terminate",None)):
                try: fn and fn()
                except: pass
            return
        if u.path=="/api/clip":
            name=q.get("name",[""])[0]; beg=q.get("beg",[""])[0]; end=q.get("end",[""])[0]
            try: path=clip_mp4(name,beg,end)
            except Exception as e: self.send_error(503,"camera busy/asleep"); return
            if not os.path.exists(path) or os.path.getsize(path)<1000:
                self.send_error(503,"no data"); return
            sz=os.path.getsize(path); rng=self.headers.get("Range")
            f=open(path,"rb")
            if rng:
                m=re.match(r"bytes=(\d+)-(\d*)",rng); a=int(m.group(1)); b=int(m.group(2)) if m.group(2) else sz-1
                b=min(b,sz-1); f.seek(a); self.send_response(206)
                self.send_header("Content-Range","bytes %d-%d/%d"%(a,b,sz)); self.send_header("Content-Length",str(b-a+1))
            else:
                a,b=0,sz-1; self.send_response(200); self.send_header("Content-Length",str(sz))
            self.send_header("Content-Type","video/mp4"); self.send_header("Accept-Ranges","bytes")
            self.end_headers()
            f.seek(a); remain=b-a+1
            while remain>0:
                chunk=f.read(min(65536,remain))
                if not chunk: break
                try: self.wfile.write(chunk)
                except: break
                remain-=len(chunk)
            f.close(); return
        self.send_error(404)

class TS(socketserver.ThreadingMixIn,http.server.HTTPServer): daemon_threads=True
if __name__=="__main__":
    print("Камера-вьювер на http://localhost:8090")
    TS(("0.0.0.0",8090),H).serve_forever()
