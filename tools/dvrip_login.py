import socket,struct,json,hashlib,os
HOST=os.environ.get("CAM_HOST","192.168.1.10");PORT=int(os.environ.get("CAM_PORT","34567"))
def sh(pw=""):
    m=hashlib.md5(pw.encode()).digest();c="0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    return "".join(c[(m[i*2]+m[i*2+1])%62] for i in range(8))
def ra(s,n):
    b=b""
    while len(b)<n:
        x=s.recv(n-len(b))
        if not x: break
        b+=x
    return b
def send(s,session,seq,mid,obj):
    body=json.dumps(obj).encode()+b"\x0a\x00"
    s.sendall(struct.pack("<BBHIIBBHI",0xff,0,0,session,seq,0,0,mid,len(body))+body)
def recv(s):
    hd=ra(s,20)
    if len(hd)<20: return None,None
    mid=struct.unpack("<H",hd[14:16])[0];ln=struct.unpack("<I",hd[16:20])[0]
    bd=ra(s,ln);txt=bd.split(b"\x00")[0].decode("utf-8","replace").strip()
    try:return mid,json.loads(txt)
    except:return mid,txt

s=socket.create_connection((HOST,PORT),6);s.settimeout(8)
send(s,0,0,1000,{"EncryptType":"MD5","LoginType":"DVRIP-Web","PassWord":sh(os.environ.get("CAM_PASS","")),"UserName":"admin"})
_,js=recv(s)
ret=js.get("Ret") if isinstance(js,dict) else js
print("LOGIN -> Ret=%s"%ret)
if isinstance(js,dict) and ret==100:
    print(">>> УСПЕХ! залогинились <<<")
    sid=int(js["SessionID"],16);sidhex=js["SessionID"];seq=1
    for name,mid in [("SystemInfo",1020),("General",1042),("NetWork.NetCommon",1042),("Users",1472),("OPSystemUpgrade",1360),("fVideo.OSD",1042)]:
        try:
            send(s,sid,seq,mid,{"Name":name,"SessionID":sidhex});seq+=1
            _,r=recv(s)
            print("\n==== %s ====\n%s"%(name, json.dumps(r,ensure_ascii=False,indent=1) if isinstance(r,(dict,list)) else r))
        except Exception as e:
            print("  %s err %s"%(name,e))
else:
    print("(не пустило: 106=неверный пароль, 205=аккаунт временно заблокирован)")
s.close()
