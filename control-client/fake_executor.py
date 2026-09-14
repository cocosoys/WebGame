import socket, struct, threading, time, urllib.parse
srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(('127.0.0.1', 25576)); srv.listen(4)
print('FAKE executor ready', flush=True)
def handle(c, a):
    print('CONN', a, flush=True)
    try:
        while True:
            head = c.recv(5)
            if len(head) < 5: break
            ln, typ = struct.unpack('>IB', head)
            p = b''
            while len(p) < ln:
                d = c.recv(ln-len(p))
                if not d: break
                p += d
            print('FRAME type=%d len=%d payload=%r' % (typ, ln, p[:200].decode('utf-8','replace')), flush=True)
            if typ == 1:
                body = b'instanceId=i1\nok=1'
                c.sendall(struct.pack('>IB', len(body), 0x81) + body)
            elif typ == 3:
                c.sendall(struct.pack('>IB', 20, 0x84) + b'instances=\ncapacity=4\nruns=0')
            elif typ == 5:
                c.sendall(struct.pack('>IB', 5, 0x85) + b'ts=0')
    except Exception as e:
        print('ERR', e, flush=True)
    finally:
        c.close()
        print('CLOSE', a, flush=True)
while True:
    c, a = srv.accept()
    threading.Thread(target=handle, args=(c,a), daemon=True).start()
