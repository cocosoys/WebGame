$ErrorActionPreference = "Stop"

$cs = @'
using System;
using System.Net.Sockets;
using System.Text;
using System.Threading;

public static class CloudWsProbe
{
    public static string Test(string user, int port)
    {
        try
        {
            using (TcpClient tcp = new TcpClient("127.0.0.1", port))
            {
                NetworkStream ns = tcp.GetStream();
                ns.ReadTimeout = 4000;
                string key = Convert.ToBase64String(new byte[16]);
                string req = "GET /cloud?user=" + user + " HTTP/1.1\r\n" +
                             "Host: 127.0.0.1:" + port + "\r\n" +
                             "Upgrade: websocket\r\n" +
                             "Connection: Upgrade\r\n" +
                             "Sec-WebSocket-Key: " + key + "\r\n" +
                             "Sec-WebSocket-Version: 13\r\n\r\n";
                byte[] reqBytes = Encoding.ASCII.GetBytes(req);
                ns.Write(reqBytes, 0, reqBytes.Length);
                ns.Flush();

                // 读 HTTP 头
                byte[] buf = new byte[4096];
                string head = "";
                while (!head.Contains("\r\n\r\n"))
                {
                    int n = ns.Read(buf, 0, buf.Length);
                    if (n <= 0) return "NO_101";
                    head += Encoding.ASCII.GetString(buf, 0, n);
                }
                string status = head.Substring(0, head.IndexOf("\r\n"));

                // 发 PONG（0x04 + counter=1），掩码帧
                byte[] pong = new byte[] { 0x04, 0x01, 0x00, 0x00, 0x00 };
                byte[] frame = MaskedFrame(0x2, pong);
                ns.Write(frame, 0, frame.Length);
                ns.Flush();

                // 读服务器帧
                int type = -1;
                string msg = "";
                byte[] fb = ReadFrame(ns);
                if (fb != null && fb.Length >= 1)
                {
                    type = fb[0] & 0xFF;
                    if (type == 0x05 && fb.Length >= 3)
                    {
                        int mlen = fb[1] | (fb[2] << 8);
                        if (fb.Length >= 3 + mlen)
                            msg = Encoding.UTF8.GetString(fb, 3, mlen);
                    }
                }
                return "STATUS=" + status + " TYPE=" + type + " MSG=" + msg;
            }
        }
        catch (Exception e)
        {
            return "EX=" + e.GetType().Name + ":" + e.Message;
        }
    }

    private static byte[] MaskedFrame(byte opcode, byte[] payload)
    {
        byte[] mask = new byte[] { 0x12, 0x34, 0x56, 0x78 };
        int len = payload.Length;
        var ms = new System.IO.MemoryStream();
        ms.WriteByte((byte)(0x80 | opcode));
        if (len < 126) ms.WriteByte((byte)(0x80 | len));
        else if (len < 65536) { ms.WriteByte(0x80 | 126); ms.WriteByte((byte)(len >> 8)); ms.WriteByte((byte)len); }
        else { ms.WriteByte(0x80 | 127); for (int i = 7; i >= 0; i--) ms.WriteByte((byte)(len >> (i * 8))); }
        ms.Write(mask, 0, 4);
        for (int i = 0; i < len; i++) ms.WriteByte((byte)(payload[i] ^ mask[i % 4]));
        return ms.ToArray();
    }

    private static byte[] ReadFrame(NetworkStream ns)
    {
        int b0 = ns.ReadByte();
        if (b0 < 0) return null;
        int b1 = ns.ReadByte();
        int len = b1 & 0x7F;
        if (len == 126) { len = (ns.ReadByte() << 8) | ns.ReadByte(); }
        else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | ns.ReadByte(); }
        byte[] data = new byte[len];
        int off = 0;
        while (off < len)
        {
            int n = ns.Read(data, off, len - off);
            if (n <= 0) break;
            off += n;
        }
        return data;
    }
}
'@

Add-Type -TypeDefinition $cs

Write-Output ("Session1: " + [CloudWsProbe]::Test("CloudTest1", 25574))
Write-Output ("Session2: " + [CloudWsProbe]::Test("CloudTest2", 25574))
Write-Output ("Session3: " + [CloudWsProbe]::Test("CloudTest3", 25574))
