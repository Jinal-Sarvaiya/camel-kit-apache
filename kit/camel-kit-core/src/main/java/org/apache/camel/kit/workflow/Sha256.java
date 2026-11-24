package org.apache.camel.kit.workflow;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 over the complete file or catalog stream, including any unread trailing bytes. */
final class Sha256 {
    private Sha256() {
    }

    static DigestInputStream stream(InputStream input) {
        try {
            return new DigestInputStream(input, MessageDigest.getInstance("SHA-256"));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String finish(DigestInputStream input) throws IOException {
        input.transferTo(OutputStream.nullOutputStream());
        return HexFormat.of().formatHex(input.getMessageDigest().digest());
    }
}
