package splusjava;

import java.io.ByteArrayOutputStream;
import java.util.Random;

import splusjava.tl.TLObject;
import splusjava.util.Bytes;
import splusjava.util.Log;

/**
 * Uploads and downloads file data. Soroush - like Telegram - moves file bytes as plain chunks over
 * the same encrypted connection as everything else, addressed by a random file id chosen by the
 * client (upload) or by the file's own id/access_hash/file_reference (download); there is no separate
 * HTTP transfer.
 */
public final class Files {
    /** Chunk size Telegram-derived clients conventionally use; must be a power of 2, 4 KiB to 512 KiB. */
    public static final int DEFAULT_PART_SIZE = 128 * 1024;
    private static final long BIG_FILE_THRESHOLD = 10L * 1024 * 1024;

    private Files() {
    }

    /** Uploads a byte array, returning the {@code InputFile} to embed in {@code inputMediaUploaded...}. */
    public static TLObject upload(SoroushClient client, byte[] data, String fileName) {
        return upload(client, data, fileName, DEFAULT_PART_SIZE);
    }

    public static TLObject upload(SoroushClient client, byte[] data, String fileName, int partSize) {
        long fileId = new Random().nextLong();
        int totalParts = (data.length + partSize - 1) / partSize;
        if (totalParts == 0) {
            totalParts = 1;
        }
        boolean big = data.length > BIG_FILE_THRESHOLD;
        for (int part = 0; part < totalParts; part++) {
            int off = part * partSize;
            int len = Math.min(partSize, data.length - off);
            byte[] chunk = Bytes.sub(data, off, Math.max(len, 0));
            TLObject req = big
                    ? TLObject.of("upload.saveBigFilePart", "file_id", Long.valueOf(fileId), "file_part", Integer.valueOf(part),
                            "file_total_parts", Integer.valueOf(totalParts), "bytes", chunk)
                    : TLObject.of("upload.saveFilePart", "file_id", Long.valueOf(fileId), "file_part", Integer.valueOf(part), "bytes", chunk);
            Object ok = client.invoke(req);
            if (!Boolean.TRUE.equals(ok)) {
                throw new SoroushException("upload.saveFilePart returned false for part " + part + "/" + totalParts);
            }
            if ((part & 15) == 0 || part == totalParts - 1) {
                Log.d("Uploaded part " + (part + 1) + "/" + totalParts + " of " + fileName);
            }
        }
        String md5 = big ? null : md5Hex(data);
        return big
                ? TLObject.of("inputFileBig", "id", Long.valueOf(fileId), "parts", Integer.valueOf(totalParts), "name", fileName)
                : TLObject.of("inputFile", "id", Long.valueOf(fileId), "parts", Integer.valueOf(totalParts), "name", fileName,
                        "md5_checksum", md5);
    }

    /** Downloads a whole file addressed by an {@code InputFileLocation} (e.g. from {@link #locationFor}). */
    public static byte[] download(SoroushClient client, TLObject location) {
        return download(client, location, DEFAULT_PART_SIZE);
    }

    public static byte[] download(SoroushClient client, TLObject location, int partSize) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long offset = 0;
        while (true) {
            TLObject resp = (TLObject) client.invoke(TLObject.of("upload.getFile",
                    "location", location, "offset", Long.valueOf(offset), "limit", Integer.valueOf(partSize)));
            byte[] chunk = resp.getBytes("bytes");
            if (chunk == null || chunk.length == 0) {
                break;
            }
            out.write(chunk, 0, chunk.length);
            offset += chunk.length;
            if (chunk.length < partSize) {
                break;
            }
        }
        return out.toByteArray();
    }

    /** Builds the {@code InputFileLocation} for a {@code Document} object (e.g. {@code message.media.document}). */
    public static TLObject locationForDocument(TLObject document) {
        return TLObject.of("inputDocumentFileLocation",
                "id", Long.valueOf(document.getLong("id")), "access_hash", Long.valueOf(document.getLong("access_hash")),
                "file_reference", document.getBytes("file_reference"), "thumb_size", "");
    }

    /** Builds the {@code InputFileLocation} for a {@code Photo} object at its largest size. */
    public static TLObject locationForPhoto(TLObject photo) {
        java.util.List<Object> sizes = photo.getList("sizes");
        String largest = "";
        for (int i = 0; i < sizes.size(); i++) {
            Object s = sizes.get(i);
            if (s instanceof TLObject) {
                String type = ((TLObject) s).getString("type");
                if (type != null) {
                    largest = type; // sizes are listed smallest to largest; keep the last one seen
                }
            }
        }
        return TLObject.of("inputPhotoFileLocation",
                "id", Long.valueOf(photo.getLong("id")), "access_hash", Long.valueOf(photo.getLong("access_hash")),
                "file_reference", photo.getBytes("file_reference"), "thumb_size", largest);
    }

    private static String md5Hex(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] h = md.digest(data);
            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < h.length; i++) {
                sb.append(Character.forDigit((h[i] >> 4) & 0xf, 16)).append(Character.forDigit(h[i] & 0xf, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
