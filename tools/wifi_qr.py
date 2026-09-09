#!/usr/bin/env python3
"""Генератор Xiongmai/iCSee Wi-Fi QR (онбординг камеры без приложения, без облака).
Формат:  S:<ssid>\nP:<pass>\nE:1\nM:<router MAC no colons>\nI:<router IP last octet>\nB:<rand>
Показать сброшенной камере (в режиме пейринга) — она прочитает QR объективом и зайдёт в сеть.
"""
import qrcode, sys, os, binascii
def make(ssid, password, router_mac="AA:BB:CC:DD:EE:FF", router_ip_last="1", enc="1", out="wifi_qr.png"):
    mac=router_mac.replace(":","").replace("-","").upper()
    b=binascii.hexlify(os.urandom(5)).decode()
    payload="S:%s\nP:%s\nE:%s\nM:%s\nI:%s\nB:%s"%(ssid,password,enc,mac,router_ip_last,b)
    qr=qrcode.QRCode(border=2); qr.add_data(payload); qr.make(fit=True)
    qr.make_image(fill_color="black",back_color="white").save(out)
    print("payload:\n"+payload); print("\nсохранён:",out)
    return out
if __name__=="__main__":
    if len(sys.argv)<3:
        print("usage: wifi_qr.py <SSID> <PASSWORD> [routerMAC] [routerIPlastOctet] [out.png]"); sys.exit(1)
    make(*sys.argv[1:])
