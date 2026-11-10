using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Text;

namespace DiscoverEnabler
{
    /// <summary>One file inside the APK. Raw (compressed) bytes are kept as they are, so untouched files are copied 1:1.</summary>
    public sealed class ZipEntry
    {
        public string Name;
        public ushort Method;          // 0 = stored, 8 = deflate
        public ushort Flags;
        public ushort Time, Date;
        public uint Crc;
        public long UncompressedSize;
        public ushort VersionMadeBy = 20, VersionNeeded = 20;
        public ushort InternalAttr;
        public uint ExternalAttr;
        public byte[] RawData;         // compressed bytes (or plain bytes when stored)

        public byte[] GetUncompressed()
        {
            if (Method == 0) return RawData;
            if (Method != 8) throw new InvalidDataException("Unsupported compression in " + Name + ": " + Method);
            using (var src = new MemoryStream(RawData))
            using (var inf = new DeflateStream(src, CompressionMode.Decompress))
            using (var dst = new MemoryStream((int)Math.Max(0, Math.Min(UncompressedSize, int.MaxValue))))
            {
                inf.CopyTo(dst);
                return dst.ToArray();
            }
        }

        public static ZipEntry Create(string name, byte[] content, bool compress, ZipEntry template = null)
        {
            var e = new ZipEntry
            {
                Name = name,
                Method = (ushort)(compress ? 8 : 0),
                Time = template?.Time ?? 0x0820,
                Date = template?.Date ?? 0x0221,   // 1981-01-01, same as the Android build tools
                Crc = Crc32.Compute(content),
                UncompressedSize = content.Length,
                ExternalAttr = template?.ExternalAttr ?? 0,
                VersionMadeBy = template?.VersionMadeBy ?? 20,
                Flags = (ushort)((template?.Flags ?? 0) & 0x0800)
            };
            if (compress)
            {
                using (var ms = new MemoryStream())
                {
                    using (var def = new DeflateStream(ms, CompressionLevel.Optimal, true)) def.Write(content, 0, content.Length);
                    e.RawData = ms.ToArray();
                }
            }
            else e.RawData = content;
            return e;
        }
    }

    /// <summary>Minimal ZIP reader/writer for APKs (no ZIP64, no encryption), with zipalign-style alignment.</summary>
    public static class ApkZip
    {
        public static List<ZipEntry> Read(byte[] f)
        {
            int eocd = FindEocd(f);
            int count = U16(f, eocd + 10);
            long cdOffset = U32(f, eocd + 16);
            var list = new List<ZipEntry>(count);
            int p = (int)cdOffset;
            for (int i = 0; i < count; i++)
            {
                if (U32(f, p) != 0x02014b50) throw new InvalidDataException("Damaged central directory");
                var e = new ZipEntry
                {
                    VersionMadeBy = U16(f, p + 4),
                    VersionNeeded = U16(f, p + 6),
                    Flags = U16(f, p + 8),
                    Method = U16(f, p + 10),
                    Time = U16(f, p + 12),
                    Date = U16(f, p + 14),
                    Crc = U32(f, p + 16),
                    UncompressedSize = U32(f, p + 24),
                    InternalAttr = U16(f, p + 36),
                    ExternalAttr = U32(f, p + 38)
                };
                long csize = U32(f, p + 20);
                int nlen = U16(f, p + 28), xlen = U16(f, p + 30), clen = U16(f, p + 32);
                long lho = U32(f, p + 42);
                e.Name = ((e.Flags & 0x0800) != 0 ? Encoding.UTF8 : Encoding.UTF8).GetString(f, p + 46, nlen);
                if (csize == 0xFFFFFFFF || lho == 0xFFFFFFFF) throw new InvalidDataException("ZIP64 is not supported");
                if (U32(f, (int)lho) != 0x04034b50) throw new InvalidDataException("Damaged local header for " + e.Name);
                int lnlen = U16(f, (int)lho + 26), lxlen = U16(f, (int)lho + 28);
                long data = lho + 30 + lnlen + lxlen;
                e.RawData = new byte[csize];
                Buffer.BlockCopy(f, (int)data, e.RawData, 0, (int)csize);
                list.Add(e);
                p += 46 + nlen + xlen + clen;
            }
            return list;
        }

        /// <summary>Writes the entries as an unsigned APK. Returns the bytes and the offset of the central directory.</summary>
        public static byte[] Write(IList<ZipEntry> entries, out long cdOffset, out long eocdOffset)
        {
            var ms = new MemoryStream();
            var offsets = new long[entries.Count];
            for (int i = 0; i < entries.Count; i++)
            {
                var e = entries[i];
                var name = Encoding.UTF8.GetBytes(e.Name);
                offsets[i] = ms.Position;
                int extraLen = 0;
                int align = 0;
                if (e.Method == 0)
                {
                    align = e.Name.EndsWith(".so", StringComparison.Ordinal) ? 16384 : 4;
                    long dataStart = ms.Position + 30 + name.Length;
                    // zipalign-style extra field 0xD935: id(2) size(2) alignment(2) + zero padding
                    extraLen = 6;
                    long rem = (dataStart + extraLen) % align;
                    if (rem != 0) extraLen += (int)(align - rem);
                }
                W32(ms, 0x04034b50);
                W16(ms, e.VersionNeeded == 0 ? (ushort)20 : e.VersionNeeded);
                W16(ms, (ushort)(e.Flags & ~0x0008));   // no data descriptor: sizes are in the header
                W16(ms, e.Method);
                W16(ms, e.Time); W16(ms, e.Date);
                W32(ms, e.Crc);
                W32(ms, (uint)e.RawData.Length);
                W32(ms, (uint)e.UncompressedSize);
                W16(ms, (ushort)name.Length);
                W16(ms, (ushort)extraLen);
                ms.Write(name, 0, name.Length);
                if (extraLen > 0)
                {
                    W16(ms, 0xD935);
                    W16(ms, (ushort)(extraLen - 4));
                    W16(ms, (ushort)align);
                    ms.Write(new byte[extraLen - 6], 0, extraLen - 6);
                }
                ms.Write(e.RawData, 0, e.RawData.Length);
            }
            cdOffset = ms.Position;
            for (int i = 0; i < entries.Count; i++)
            {
                var e = entries[i];
                var name = Encoding.UTF8.GetBytes(e.Name);
                W32(ms, 0x02014b50);
                W16(ms, e.VersionMadeBy);
                W16(ms, e.VersionNeeded == 0 ? (ushort)20 : e.VersionNeeded);
                W16(ms, (ushort)(e.Flags & ~0x0008));
                W16(ms, e.Method);
                W16(ms, e.Time); W16(ms, e.Date);
                W32(ms, e.Crc);
                W32(ms, (uint)e.RawData.Length);
                W32(ms, (uint)e.UncompressedSize);
                W16(ms, (ushort)name.Length);
                W16(ms, 0); W16(ms, 0);    // extra, comment
                W16(ms, 0);                // disk
                W16(ms, e.InternalAttr);
                W32(ms, e.ExternalAttr);
                W32(ms, (uint)offsets[i]);
                ms.Write(name, 0, name.Length);
            }
            long cdEnd = ms.Position;
            eocdOffset = cdEnd;
            W32(ms, 0x06054b50);
            W16(ms, 0); W16(ms, 0);
            W16(ms, (ushort)entries.Count); W16(ms, (ushort)entries.Count);
            W32(ms, (uint)(cdEnd - cdOffset));
            W32(ms, (uint)cdOffset);
            W16(ms, 0);
            if (ms.Length > uint.MaxValue) throw new InvalidDataException("APK too large");
            return ms.ToArray();
        }

        public static int FindEocd(byte[] f)
        {
            for (int i = f.Length - 22; i >= Math.Max(0, f.Length - 22 - 65535); i--)
                if (U32(f, i) == 0x06054b50 && i + 22 + U16(f, i + 20) == f.Length) return i;
            throw new InvalidDataException("This is not a valid APK/ZIP file");
        }

        internal static ushort U16(byte[] b, int o) => (ushort)(b[o] | b[o + 1] << 8);
        internal static uint U32(byte[] b, int o) => (uint)(b[o] | b[o + 1] << 8 | b[o + 2] << 16 | b[o + 3] << 24);
        static void W16(Stream s, ushort v) { s.WriteByte((byte)v); s.WriteByte((byte)(v >> 8)); }
        static void W32(Stream s, uint v) { W16(s, (ushort)v); W16(s, (ushort)(v >> 16)); }
    }

    public static class Crc32
    {
        static readonly uint[] Table = Build();
        static uint[] Build()
        {
            var t = new uint[256];
            for (uint i = 0; i < 256; i++)
            {
                uint c = i;
                for (int k = 0; k < 8; k++) c = (c & 1) != 0 ? 0xEDB88320 ^ (c >> 1) : c >> 1;
                t[i] = c;
            }
            return t;
        }
        public static uint Compute(byte[] data)
        {
            uint c = 0xFFFFFFFF;
            foreach (var b in data) c = Table[(c ^ b) & 0xFF] ^ (c >> 8);
            return c ^ 0xFFFFFFFF;
        }
    }
}
