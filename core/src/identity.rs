use nostr::nips::nip06::FromMnemonic;
use nostr::nips::nip19::ToBech32;
use nostr::Keys;
use serde::Serialize;
use thiserror::Error;

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct NostrIdentity {
    pub private_key_hex: String,
    pub nsec: String,
}

#[derive(Debug, Error)]
pub enum IdentityError {
    #[error("mnemonic is invalid: {0}")]
    InvalidMnemonic(String),
    #[error("bech32 encoding failed")]
    Bech32Encoding,
}

pub fn derive_nostr_identity(
    mnemonic: &str,
    passphrase: &str,
) -> Result<NostrIdentity, IdentityError> {
    let keys = Keys::from_mnemonic_advanced(mnemonic, Some(passphrase), Some(0), Some(0), Some(0))
        .map_err(|err| IdentityError::InvalidMnemonic(err.to_string()))?;
    let private_key_hex = keys.secret_key().to_secret_hex();
    let nsec = keys
        .secret_key()
        .to_bech32()
        .map_err(|_| IdentityError::Bech32Encoding)?;

    Ok(NostrIdentity {
        private_key_hex,
        nsec,
    })
}
