// Minecraft's servers.dat, shared by OctoberJourneySetup.exe (adds the server) and OctoberJourneyUninstall.exe (removes it).
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;

// Minecraft's servers.dat (uncompressed NBT): adds a server unless one with that address is already listed.
static class Nbt
{
    class Tag { public byte Type; public string Name; public object Value; public byte ListType; }

    public static void AddServer(string path, string name, string address)
    {
        Tag root;
        if (File.Exists(path)) using (var r = new Be(File.OpenRead(path))) { byte t = r.ReadByte(); root = new Tag { Type = t, Name = r.Str() }; root.Value = ReadPayload(r, t, root); }
        else root = new Tag { Type = 10, Name = "", Value = new List<Tag>() };
        var top = (List<Tag>)root.Value;
        var servers = top.FirstOrDefault(t => t.Name == "servers");
        if (servers == null) { servers = new Tag { Type = 9, Name = "servers", ListType = 10, Value = new List<object>() }; top.Add(servers); }
        var list = (List<object>)servers.Value;
        servers.ListType = 10;
        foreach (List<Tag> s in list) if (s.Any(t => t.Name == "ip" && string.Equals((string)t.Value, address, StringComparison.OrdinalIgnoreCase))) return;
        list.Add(new List<Tag> { new Tag { Type = 8, Name = "name", Value = name }, new Tag { Type = 8, Name = "ip", Value = address }, new Tag { Type = 1, Name = "acceptTextures", Value = (byte)1 } });
        using (var w = new Be(File.Create(path), true)) { w.Write((byte)10); w.Str(""); WritePayload(w, root); }
    }

    // Removes every server whose address or name is in the given lists; returns how many went.
    public static int RemoveServers(string path, ICollection<string> addresses, ICollection<string> names)
    {
        if (!File.Exists(path)) return 0;
        Tag root;
        using (var r = new Be(File.OpenRead(path))) { byte t = r.ReadByte(); root = new Tag { Type = t, Name = r.Str() }; root.Value = ReadPayload(r, t, root); }
        var servers = ((List<Tag>)root.Value).FirstOrDefault(t => t.Name == "servers");
        if (servers == null) return 0;
        var list = (List<object>)servers.Value;
        int before = list.Count;
        list.RemoveAll(o => ((List<Tag>)o).Any(t => t.Type == 8 && ((t.Name == "ip" && addresses.Any(a => string.Equals(a, (string)t.Value, StringComparison.OrdinalIgnoreCase))) || (t.Name == "name" && names.Contains((string)t.Value)))));
        int gone = before - list.Count;
        if (gone > 0) using (var w = new Be(File.Create(path), true)) { w.Write((byte)10); w.Str(""); WritePayload(w, root); }
        return gone;
    }

    static object ReadPayload(Be r, byte t, Tag owner)
    {
        switch (t)
        {
            case 1: return r.ReadByte();
            case 2: return r.I16();
            case 3: return r.I32();
            case 4: return r.I64();
            case 5: return r.I32();
            case 6: return r.I64();
            case 7: return r.ReadBytes(r.I32());
            case 8: return r.Str();
            case 9: { byte lt = r.ReadByte(); int n = r.I32(); var l = new List<object>(); if (owner != null) owner.ListType = lt; for (int i = 0; i < n; i++) l.Add(ReadPayload(r, lt, null)); return l; }
            case 10: { var l = new List<Tag>(); while (true) { byte ct = r.ReadByte(); if (ct == 0) return l; var c = new Tag { Type = ct, Name = r.Str() }; c.Value = ReadPayload(r, ct, c); l.Add(c); } }
            case 11: { int n = r.I32(); var a = new int[n]; for (int i = 0; i < n; i++) a[i] = r.I32(); return a; }
            case 12: { int n = r.I32(); var a = new long[n]; for (int i = 0; i < n; i++) a[i] = r.I64(); return a; }
        }
        throw new Exception("unexpected NBT tag " + t);
    }

    static void WritePayload(Be w, Tag tag) { WriteValue(w, tag.Type, tag.Value, tag.ListType); }
    static void WriteValue(Be w, byte t, object v, byte listType)
    {
        switch (t)
        {
            case 1: w.Write((byte)v); break;
            case 2: w.I16((short)v); break;
            case 3: case 5: w.I32((int)v); break;
            case 4: case 6: w.I64((long)v); break;
            case 7: { var b = (byte[])v; w.I32(b.Length); w.Write(b); break; }
            case 8: w.Str((string)v); break;
            case 9: { var l = (List<object>)v; w.Write(l.Count == 0 ? (byte)0 : listType); w.I32(l.Count); foreach (var x in l) WriteValue(w, listType, x, 10); break; }
            case 10: { foreach (var c in (List<Tag>)v) { w.Write(c.Type); w.Str(c.Name); WriteValue(w, c.Type, c.Value, c.ListType); } w.Write((byte)0); break; }
            case 11: { var a = (int[])v; w.I32(a.Length); foreach (var x in a) w.I32(x); break; }
            case 12: { var a = (long[])v; w.I32(a.Length); foreach (var x in a) w.I64(x); break; }
        }
    }

    // Big-endian reading and writing.
    class Be : IDisposable
    {
        readonly Stream s; readonly bool writing;
        public Be(Stream s, bool writing = false) { this.s = s; this.writing = writing; }
        public byte ReadByte() { int b = s.ReadByte(); if (b < 0) throw new EndOfStreamException(); return (byte)b; }
        public byte[] ReadBytes(int n) { var b = new byte[n]; int o = 0; while (o < n) { int k = s.Read(b, o, n - o); if (k <= 0) throw new EndOfStreamException(); o += k; } return b; }
        public short I16() { var b = ReadBytes(2); return (short)(b[0] << 8 | b[1]); }
        public int I32() { var b = ReadBytes(4); return b[0] << 24 | b[1] << 16 | b[2] << 8 | b[3]; }
        public long I64() { return (long)(uint)I32() << 32 | (uint)I32(); }
        public string Str() { int n = (ushort)I16(); return Encoding.UTF8.GetString(ReadBytes(n)); }
        public void Write(byte b) { s.WriteByte(b); }
        public void Write(byte[] b) { s.Write(b, 0, b.Length); }
        public void I16(short v) { Write(new[] { (byte)(v >> 8), (byte)v }); }
        public void I32(int v) { Write(new[] { (byte)(v >> 24), (byte)(v >> 16), (byte)(v >> 8), (byte)v }); }
        public void I64(long v) { I32((int)(v >> 32)); I32((int)v); }
        public void Str(string v) { var b = Encoding.UTF8.GetBytes(v); I16((short)b.Length); Write(b); }
        public void Dispose() { s.Dispose(); }
    }
}
