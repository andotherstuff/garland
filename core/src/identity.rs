use bech32::{encode, Bech32, Hrp};
use bip32::{DerivationPath, XPrv};
use bip39::{Language, Mnemonic};
use serde::Serialize;
use std::str::FromStr;
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
    #[error("derivation path is invalid")]
    InvalidPath,
    #[error("bech32 encoding failed")]
    Bech32Encoding,
}

pub fn derive_nostr_identity(
    mnemonic: &str,
    passphrase: &str,
) -> Result<NostrIdentity, IdentityError> {
    let mnemonic = Mnemonic::parse_in_normalized(Language::English, mnemonic)
        .map_err(|err| IdentityError::InvalidMnemonic(err.to_string()))?;
    let seed = mnemonic.to_seed_normalized(passphrase);
    let path =
        DerivationPath::from_str("m/44'/1237'/0'/0/0").map_err(|_| IdentityError::InvalidPath)?;
    let child = XPrv::derive_from_path(&seed, &path).map_err(|_| IdentityError::InvalidPath)?;
    let private_key_bytes = child.private_key().to_bytes();
    let private_key_hex = hex::encode(private_key_bytes);
    let nsec = encode::<Bech32>(
        Hrp::parse("nsec").map_err(|_| IdentityError::Bech32Encoding)?,
        &private_key_bytes,
    )
    .map_err(|_| IdentityError::Bech32Encoding)?;

    Ok(NostrIdentity {
        private_key_hex,
        nsec,
    })
}
