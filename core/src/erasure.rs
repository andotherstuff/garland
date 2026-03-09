use reed_solomon_erasure::galois_8::ReedSolomon;
use sha2::{Digest, Sha256};
use thiserror::Error;

/// Default erasure coding parameters: 3-of-5.
/// Any 3 shares can reconstruct the original block.
pub const DEFAULT_DATA_SHARDS: usize = 3;
pub const DEFAULT_PARITY_SHARDS: usize = 2;
pub const DEFAULT_TOTAL_SHARDS: usize = DEFAULT_DATA_SHARDS + DEFAULT_PARITY_SHARDS;

#[derive(Debug, Error)]
pub enum ErasureError {
    #[error("Reed-Solomon construction failed: {0}")]
    Construction(String),
    #[error("block size {0} is not divisible by k={1} — pad to {2} first")]
    BlockNotDivisible(usize, usize, usize),
    #[error("Reed-Solomon encode failed: {0}")]
    EncodeFailed(String),
    #[error("Reed-Solomon reconstruct failed: {0}")]
    ReconstructFailed(String),
    #[error("need at least k={0} shares to reconstruct, got {1}")]
    InsufficientShares(usize, usize),
    #[error("share sizes are inconsistent")]
    InconsistentShareSizes,
    #[error("share index {0} is out of range for n={1}")]
    ShareIndexOutOfRange(usize, usize),
}

/// A single erasure-coded share with its index and content hash.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ErasureShare {
    /// Share index (0..n). Indices 0..k are data shards, k..n are parity.
    pub index: usize,
    /// SHA-256 hex of the share body.
    pub share_id_hex: String,
    /// The share body bytes.
    pub body: Vec<u8>,
}

/// Result of encoding a block into erasure-coded shares.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ErasureEncoded {
    pub k: usize,
    pub n: usize,
    pub share_size: usize,
    pub shares: Vec<ErasureShare>,
}

/// Pad `data` so its length is a multiple of `k`.
/// Returns the padded data and the original length.
pub fn pad_for_k(data: &[u8], k: usize) -> (Vec<u8>, usize) {
    let original_len = data.len();
    let remainder = original_len % k;
    if remainder == 0 {
        return (data.to_vec(), original_len);
    }
    let pad_len = k - remainder;
    let mut padded = Vec::with_capacity(original_len + pad_len);
    padded.extend_from_slice(data);
    padded.resize(original_len + pad_len, 0);
    (padded, original_len)
}

/// Encode a block into `n` erasure-coded shares using Reed-Solomon GF(2^8).
///
/// `data` length must be divisible by `k`. Use `pad_for_k` first if needed.
/// Returns `n` shares, each of size `data.len() / k`.
pub fn rs_encode(data: &[u8], k: usize, parity: usize) -> Result<ErasureEncoded, ErasureError> {
    let n = k + parity;
    if data.len() % k != 0 {
        let next_multiple = ((data.len() / k) + 1) * k;
        return Err(ErasureError::BlockNotDivisible(
            data.len(),
            k,
            next_multiple,
        ));
    }

    let rs = ReedSolomon::new(k, parity).map_err(|e| ErasureError::Construction(e.to_string()))?;

    let shard_size = data.len() / k;

    // Split data into k data shards
    let mut shards: Vec<Vec<u8>> = Vec::with_capacity(n);
    for i in 0..k {
        let start = i * shard_size;
        shards.push(data[start..start + shard_size].to_vec());
    }
    // Add empty parity shards
    for _ in 0..parity {
        shards.push(vec![0u8; shard_size]);
    }

    // Encode parity
    rs.encode(&mut shards)
        .map_err(|e| ErasureError::EncodeFailed(e.to_string()))?;

    let shares = shards
        .into_iter()
        .enumerate()
        .map(|(index, body)| {
            let share_id_hex = hex::encode(Sha256::digest(&body));
            ErasureShare {
                index,
                share_id_hex,
                body,
            }
        })
        .collect();

    Ok(ErasureEncoded {
        k,
        n,
        share_size: shard_size,
        shares,
    })
}

/// Reconstruct the original data from any `k` of `n` shares.
///
/// `available` contains `(share_index, share_body)` pairs.
/// Returns the reconstructed data of length `k * share_size`.
/// Caller must truncate to the original unpadded length if padding was used.
pub fn rs_reconstruct(
    available: &[(usize, &[u8])],
    k: usize,
    n: usize,
) -> Result<Vec<u8>, ErasureError> {
    if available.len() < k {
        return Err(ErasureError::InsufficientShares(k, available.len()));
    }

    let parity = n - k;
    let rs = ReedSolomon::new(k, parity).map_err(|e| ErasureError::Construction(e.to_string()))?;

    // Determine shard size from the first available share
    let shard_size = available[0].1.len();
    if available.iter().any(|(_, body)| body.len() != shard_size) {
        return Err(ErasureError::InconsistentShareSizes);
    }

    // Build the shard array with Option slots
    let mut shards: Vec<Option<Vec<u8>>> = vec![None; n];
    for &(index, body) in available {
        if index >= n {
            return Err(ErasureError::ShareIndexOutOfRange(index, n));
        }
        shards[index] = Some(body.to_vec());
    }

    // Reconstruct missing shards
    rs.reconstruct(&mut shards)
        .map_err(|e| ErasureError::ReconstructFailed(e.to_string()))?;

    // Reassemble data from the first k shards (data shards)
    let mut data = Vec::with_capacity(k * shard_size);
    for shard in &shards[..k] {
        data.extend_from_slice(shard.as_ref().expect("data shard should be reconstructed"));
    }
    Ok(data)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn encode_decode_round_trip_3_of_5() {
        let data = b"hello world, this is a test of reed-solomon erasure coding in garland!";
        let (padded, original_len) = pad_for_k(data, 3);

        let encoded = rs_encode(&padded, 3, 2).expect("encode should succeed");
        assert_eq!(encoded.k, 3);
        assert_eq!(encoded.n, 5);
        assert_eq!(encoded.shares.len(), 5);

        // All share sizes should be equal
        let shard_size = encoded.share_size;
        for share in &encoded.shares {
            assert_eq!(share.body.len(), shard_size);
        }

        // Reconstruct from all 5 shares
        let available: Vec<(usize, &[u8])> = encoded
            .shares
            .iter()
            .map(|s| (s.index, s.body.as_slice()))
            .collect();
        let reconstructed = rs_reconstruct(&available, 3, 5).expect("reconstruct should succeed");
        assert_eq!(&reconstructed[..original_len], data.as_slice());
    }

    #[test]
    fn reconstruct_with_minimum_k_shares() {
        let data = b"minimum shares test data for garland erasure coding verification";
        let (padded, original_len) = pad_for_k(data, 3);

        let encoded = rs_encode(&padded, 3, 2).expect("encode should succeed");

        // Use only shares 0, 2, 4 (skip 1 and 3)
        let available: Vec<(usize, &[u8])> = vec![
            (0, &encoded.shares[0].body),
            (2, &encoded.shares[2].body),
            (4, &encoded.shares[4].body),
        ];
        let reconstructed = rs_reconstruct(&available, 3, 5).expect("reconstruct should succeed");
        assert_eq!(&reconstructed[..original_len], data.as_slice());
    }

    #[test]
    fn reconstruct_from_only_parity_and_one_data() {
        let data = b"parity-heavy reconstruction test for garland protocol compliance";
        let (padded, original_len) = pad_for_k(data, 3);

        let encoded = rs_encode(&padded, 3, 2).expect("encode should succeed");

        // Use data shard 1 plus both parity shards (3, 4)
        let available: Vec<(usize, &[u8])> = vec![
            (1, &encoded.shares[1].body),
            (3, &encoded.shares[3].body),
            (4, &encoded.shares[4].body),
        ];
        let reconstructed = rs_reconstruct(&available, 3, 5).expect("reconstruct should succeed");
        assert_eq!(&reconstructed[..original_len], data.as_slice());
    }

    #[test]
    fn fails_with_fewer_than_k_shares() {
        let data = b"too few shares";
        let (padded, _) = pad_for_k(data, 3);
        let encoded = rs_encode(&padded, 3, 2).expect("encode should succeed");

        let available: Vec<(usize, &[u8])> =
            vec![(0, &encoded.shares[0].body), (1, &encoded.shares[1].body)];
        let err = rs_reconstruct(&available, 3, 5).expect_err("should fail with 2 < k=3");
        assert!(matches!(err, ErasureError::InsufficientShares(3, 2)));
    }

    #[test]
    fn each_share_has_unique_hash() {
        let data = b"unique hash test for garland share identification";
        let (padded, _) = pad_for_k(data, 3);

        let encoded = rs_encode(&padded, 3, 2).expect("encode should succeed");

        let ids: Vec<&str> = encoded
            .shares
            .iter()
            .map(|s| s.share_id_hex.as_str())
            .collect();
        for i in 0..ids.len() {
            for j in (i + 1)..ids.len() {
                assert_ne!(
                    ids[i], ids[j],
                    "shares {} and {} should have different hashes",
                    i, j
                );
            }
        }
    }

    #[test]
    fn pad_for_k_leaves_divisible_data_unchanged() {
        let data = vec![1u8; 9]; // 9 is divisible by 3
        let (padded, original_len) = pad_for_k(&data, 3);
        assert_eq!(padded.len(), 9);
        assert_eq!(original_len, 9);
    }

    #[test]
    fn pad_for_k_pads_to_next_multiple() {
        let data = vec![1u8; 10]; // 10 % 3 = 1, needs padding to 12
        let (padded, original_len) = pad_for_k(&data, 3);
        assert_eq!(padded.len(), 12);
        assert_eq!(original_len, 10);
        assert_eq!(&padded[..10], &data[..]);
        assert_eq!(&padded[10..], &[0, 0]);
    }

    #[test]
    fn rejects_block_not_divisible_by_k() {
        let data = vec![1u8; 10]; // not divisible by 3
        let err = rs_encode(&data, 3, 2).expect_err("should reject non-divisible");
        assert!(matches!(err, ErasureError::BlockNotDivisible(10, 3, 12)));
    }

    #[test]
    fn handles_empty_data() {
        let data = b"";
        let (padded, original_len) = pad_for_k(data, 3);
        assert_eq!(padded.len(), 0);
        assert_eq!(original_len, 0);
    }

    #[test]
    fn encode_decode_with_block_sized_data() {
        // Simulate a realistic Garland encrypted block
        let data = vec![0xABu8; 262_140]; // 262140 is divisible by 3 (= 87380 * 3)
        let encoded = rs_encode(&data, 3, 2).expect("encode should succeed");
        assert_eq!(encoded.share_size, 87_380);
        assert_eq!(encoded.shares.len(), 5);

        // Drop shares 0 and 3, reconstruct from 1, 2, 4
        let available: Vec<(usize, &[u8])> = vec![
            (1, &encoded.shares[1].body),
            (2, &encoded.shares[2].body),
            (4, &encoded.shares[4].body),
        ];
        let reconstructed = rs_reconstruct(&available, 3, 5).expect("reconstruct should succeed");
        assert_eq!(reconstructed, data);
    }

    #[test]
    fn share_index_out_of_range_rejected() {
        let data = vec![0u8; 9];
        let encoded = rs_encode(&data, 3, 2).expect("encode should succeed");

        let available: Vec<(usize, &[u8])> = vec![
            (0, &encoded.shares[0].body),
            (1, &encoded.shares[1].body),
            (99, &encoded.shares[2].body), // wrong index
        ];
        let err = rs_reconstruct(&available, 3, 5).expect_err("should reject out-of-range");
        assert!(matches!(err, ErasureError::ShareIndexOutOfRange(99, 5)));
    }
}
