package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM-pure tests for the minis:// missing-file reason classification
 * (batch23 §23c-1). The production file ChatLinkMissingReason.kt +
 * DeepLinkPathGuard.kt are compiled verbatim — no stubs needed (both
 * JVM-pure by design).
 */
class ChatLinkMissingReasonTest {

    @Test fun dotDotEscapeIsIllegalPath() {
        // The actual bad URL from the 2026-09-16 incident.
        assertEquals(
            ChatLinkMissingReason.ILLEGAL_PATH,
            chatLinkMissingReason("minis://shared/../mounts/x.md"),
        )
    }

    @Test fun rootEscapeIsIllegalPath() {
        assertEquals(
            ChatLinkMissingReason.ILLEGAL_PATH,
            chatLinkMissingReason("minis://../../etc/passwd"),
        )
    }

    @Test fun percentEncodedDotDotIsIllegalPath() {
        // %2e%2e decodes to '..' before the guard — decode happens first.
        assertEquals(
            ChatLinkMissingReason.ILLEGAL_PATH,
            chatLinkMissingReason("minis://shared/%2e%2e/mounts/x.md"),
        )
    }

    @Test fun backslashSmuggleIsIllegalPath() {
        assertEquals(
            ChatLinkMissingReason.ILLEGAL_PATH,
            chatLinkMissingReason("minis://workspace\\\\..\\\\..\\\\x.md"),
        )
    }

    @Test fun wellFormedMissingPathIsFileMissing() {
        assertEquals(
            ChatLinkMissingReason.FILE_MISSING,
            chatLinkMissingReason("minis://attachments/gone.png"),
        )
    }

    @Test fun wellFormedSessionScopedPathIsFileMissing() {
        assertEquals(
            ChatLinkMissingReason.FILE_MISSING,
            chatLinkMissingReason("minis://browser/stale.html"),
        )
    }

    @Test fun querySuffixStrippedBeforeGuard() {
        // '?' separates the query in minis:// URLs; the guard must not see it.
        assertEquals(
            ChatLinkMissingReason.FILE_MISSING,
            chatLinkMissingReason("minis://attachments/x.png?width=100"),
        )
    }

    @Test fun emptySegmentsAreIgnored() {
        // '//' is a legitimate double-slash the resolver collapses.
        assertEquals(
            ChatLinkMissingReason.FILE_MISSING,
            chatLinkMissingReason("minis://attachments//x.png"),
        )
    }

    @Test fun nonMinisUrlClassifiedByPathOnly() {
        // Resolve only hands minis:// links here; the classifier still treats
        // a bare path as a path — dot-segments are illegal regardless.
        assertEquals(
            ChatLinkMissingReason.ILLEGAL_PATH,
            chatLinkMissingReason("anything/../x"),
        )
    }
}
