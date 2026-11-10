using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Security.Cryptography.Pkcs;
using System.Security.Cryptography.X509Certificates;
using System.Text;

namespace DiscoverPatcher
{
    /// <summary>
    /// Signs an APK with JAR signing (v1) and APK Signature Scheme v2, using a self-signed key that is kept
    /// on this computer. The same key every time means a newer patched APK installs over an older one.
    /// </summary>
    public static class ApkSigner
    {
        static readonly string[] V1Ext = { ".SF", ".RSA", ".DSA", ".EC" };

        public static bool IsSignatureFile(string name)
        {
            if (!name.StartsWith("META-INF/", StringComparison.OrdinalIgnoreCase)) return false;
            var rest = name.Substring(9);
            if (rest.Contains('/')) return false;
            if (rest.Equals("MANIFEST.MF", StringComparison.OrdinalIgnoreCase)) return true;
            return V1Ext.Any(x => rest.EndsWith(x, StringComparison.OrdinalIgnoreCase));
        }

        // ---------- key ----------

        public static X509Certificate2 LoadOrCreateKey(string path)
        {
            const string pwd = "discover-patcher";
            path = Path.GetFullPath(path);
            if (File.Exists(path))
                return new X509Certificate2(File.ReadAllBytes(path), pwd, X509KeyStorageFlags.Exportable | X509KeyStorageFlags.EphemeralKeySet);
            Directory.CreateDirectory(Path.GetDirectoryName(path));
            using (var rsa = RSA.Create(2048))
            {
                var req = new CertificateRequest("CN=Discover Patcher, O=Discover Media", rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
                var now = DateTimeOffset.UtcNow.Date;
                using (var cert = req.CreateSelfSigned(now.AddDays(-1), now.AddYears(30)))
                {
                    File.WriteAllBytes(path, cert.Export(X509ContentType.Pfx, pwd));
                }
            }
            return new X509Certificate2(File.ReadAllBytes(path), pwd, X509KeyStorageFlags.Exportable | X509KeyStorageFlags.EphemeralKeySet);
        }

        public static string DefaultKeyPath()
        {
            var baseDir = Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);
            if (string.IsNullOrEmpty(baseDir)) baseDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".config");
            return Path.Combine(baseDir, "DiscoverPatcher", "signing-key.pfx");
        }

        // ---------- v1 (JAR) ----------

        public static void AddV1(List<ZipEntry> entries, X509Certificate2 cert)
        {
            entries.RemoveAll(e => IsSignatureFile(e.Name));
            var mf = new StringBuilder("Manifest-Version: 1.0\r\nCreated-By: 1.0 (Discover Patcher)\r\n\r\n");
            var sf = new StringBuilder();
            var sections = new List<string>();
            using (var sha = SHA256.Create())
            {
                foreach (var e in entries)
                {
                    if (e.Name.EndsWith("/")) continue;
                    var digest = Convert.ToBase64String(sha.ComputeHash(e.GetUncompressed()));
                    var section = Wrap("Name: " + e.Name) + "SHA-256-Digest: " + digest + "\r\n\r\n";
                    mf.Append(section);
                    sections.Add(Wrap("Name: " + e.Name) + "SHA-256-Digest: " + Convert.ToBase64String(sha.ComputeHash(Encoding.UTF8.GetBytes(section))) + "\r\n\r\n");
                }
                var mfBytes = Encoding.UTF8.GetBytes(mf.ToString());
                sf.Append("Signature-Version: 1.0\r\nCreated-By: 1.0 (Discover Patcher)\r\n");
                sf.Append("SHA-256-Digest-Manifest: " + Convert.ToBase64String(sha.ComputeHash(mfBytes)) + "\r\n");
                sf.Append("X-Android-APK-Signed: 2\r\n\r\n");
                foreach (var s in sections) sf.Append(s);
                var sfBytes = Encoding.UTF8.GetBytes(sf.ToString());

                var cms = new SignedCms(new ContentInfo(sfBytes), detached: true);
                var signer = new CmsSigner(SubjectIdentifierType.IssuerAndSerialNumber, cert)
                {
                    DigestAlgorithm = new Oid("2.16.840.1.101.3.4.2.1"),   // SHA-256
                    IncludeOption = X509IncludeOption.EndCertOnly
                };
                cms.ComputeSignature(signer);
                var sig = cms.Encode();

                var tmpl = entries.FirstOrDefault();
                entries.Insert(0, ZipEntry.Create("META-INF/CERT.RSA", sig, true, tmpl));
                entries.Insert(0, ZipEntry.Create("META-INF/CERT.SF", sfBytes, true, tmpl));
                entries.Insert(0, ZipEntry.Create("META-INF/MANIFEST.MF", mfBytes, true, tmpl));
            }
        }

        // Manifest lines may be at most 72 bytes; longer ones continue on the next line after one space.
        static string Wrap(string line)
        {
            var bytes = Encoding.UTF8.GetBytes(line);
            if (bytes.Length <= 70) return line + "\r\n";
            var sb = new StringBuilder();
            int pos = 0, max = 70;
            while (pos < bytes.Length)
            {
                int n = Math.Min(max, bytes.Length - pos);
                // never split inside a UTF-8 sequence
                while (n > 1 && pos + n < bytes.Length && (bytes[pos + n] & 0xC0) == 0x80) n--;
                if (pos > 0) sb.Append(' ');
                sb.Append(Encoding.UTF8.GetString(bytes, pos, n)).Append("\r\n");
                pos += n;
                max = 69;
            }
            return sb.ToString();
        }

        // ---------- v2 ----------

        const uint SigRsaPkcs1Sha256 = 0x0103;
        const uint V2BlockId = 0x7109871a;

        public static byte[] AddV2(byte[] apk, long cdOffset, long eocdOffset, X509Certificate2 cert)
        {
            var section1 = new ArraySegment<byte>(apk, 0, (int)cdOffset);
            var section3 = new ArraySegment<byte>(apk, (int)cdOffset, (int)(eocdOffset - cdOffset));
            var section4 = new ArraySegment<byte>(apk, (int)eocdOffset, apk.Length - (int)eocdOffset);
            var digest = ContentDigest(new[] { section1, section3, section4 });

            byte[] certDer = cert.RawData;
            byte[] spki = cert.PublicKey.ExportSubjectPublicKeyInfo();

            var signedData = Concat(
                LenPrefixed(LenPrefixed(Concat(U32(SigRsaPkcs1Sha256), LenPrefixed(digest)))),   // digests
                LenPrefixed(LenPrefixed(certDer)),                                                  // certificates
                LenPrefixed(Array.Empty<byte>()));                                                  // additional attributes

            byte[] signature;
            using (var rsa = cert.GetRSAPrivateKey())
                signature = rsa.SignData(signedData, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);

            var signer = Concat(
                LenPrefixed(signedData),
                LenPrefixed(LenPrefixed(Concat(U32(SigRsaPkcs1Sha256), LenPrefixed(signature)))),
                LenPrefixed(spki));
            var value = LenPrefixed(LenPrefixed(signer));

            // APK Signing Block
            var pair = Concat(U64((ulong)(4 + value.Length)), U32(V2BlockId), value);
            ulong blockSize = (ulong)(pair.Length + 8 + 16);
            var block = Concat(U64(blockSize), pair, U64(blockSize), Encoding.ASCII.GetBytes("APK Sig Block 42"));

            var outp = new byte[apk.Length + block.Length];
            Buffer.BlockCopy(apk, 0, outp, 0, (int)cdOffset);
            Buffer.BlockCopy(block, 0, outp, (int)cdOffset, block.Length);
            Buffer.BlockCopy(apk, (int)cdOffset, outp, (int)cdOffset + block.Length, apk.Length - (int)cdOffset);
            // EOCD: central directory now starts after the signing block
            int eocd = (int)eocdOffset + block.Length;
            var newCd = BitConverter.GetBytes((uint)(cdOffset + block.Length));
            if (!BitConverter.IsLittleEndian) Array.Reverse(newCd);
            Buffer.BlockCopy(newCd, 0, outp, eocd + 16, 4);
            return outp;
        }

        static byte[] ContentDigest(ArraySegment<byte>[] sections)
        {
            const int Chunk = 1024 * 1024;
            var chunkDigests = new List<byte[]>();
            using (var sha = SHA256.Create())
            {
                foreach (var s in sections)
                {
                    for (int off = 0; off < s.Count; off += Chunk)
                    {
                        int n = Math.Min(Chunk, s.Count - off);
                        var buf = new byte[5 + n];
                        buf[0] = 0xa5;
                        Buffer.BlockCopy(U32((uint)n), 0, buf, 1, 4);
                        Buffer.BlockCopy(s.Array, s.Offset + off, buf, 5, n);
                        chunkDigests.Add(sha.ComputeHash(buf));
                    }
                }
                var top = new byte[5 + chunkDigests.Count * 32];
                top[0] = 0x5a;
                Buffer.BlockCopy(U32((uint)chunkDigests.Count), 0, top, 1, 4);
                for (int i = 0; i < chunkDigests.Count; i++) Buffer.BlockCopy(chunkDigests[i], 0, top, 5 + i * 32, 32);
                return sha.ComputeHash(top);
            }
        }

        static byte[] U32(uint v) => new[] { (byte)v, (byte)(v >> 8), (byte)(v >> 16), (byte)(v >> 24) };
        static byte[] U64(ulong v) { var b = new byte[8]; for (int i = 0; i < 8; i++) b[i] = (byte)(v >> (8 * i)); return b; }
        static byte[] LenPrefixed(byte[] data) => Concat(U32((uint)data.Length), data);
        static byte[] Concat(params byte[][] parts)
        {
            var r = new byte[parts.Sum(p => p.Length)];
            int o = 0;
            foreach (var p in parts) { Buffer.BlockCopy(p, 0, r, o, p.Length); o += p.Length; }
            return r;
        }
    }
}
