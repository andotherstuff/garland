package com.andotherstuff.garland

object NativeBridge {
    init {
        System.loadLibrary("garland_core")
    }

    external fun deriveIdentity(mnemonic: String, passphrase: String): String
    external fun prepareSingleBlockWrite(requestJson: String): String
    external fun recoverSingleBlockRead(requestJson: String): String
    external fun signCustomEvent(requestJson: String): String
    external fun prepareCommitChainSnapshot(requestJson: String): String
    external fun resolveCommitChainHead(requestJson: String): String
    external fun readDirectoryEntries(requestJson: String): String
    external fun signBlossomUploadAuth(requestJson: String): String
}
