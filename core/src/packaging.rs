use rand::RngCore;
use thiserror::Error;

pub const BLOCK_SIZE: usize = 262_144;
pub const FRAME_SIZE: usize = 262_100;
pub const CONTENT_CAPACITY: usize = FRAME_SIZE - 4;

#[derive(Debug, Error)]
pub enum PackagingError {
    #[error("content too large for a single Garland frame")]
    ContentTooLarge,
    #[error("frame length is invalid")]
    InvalidFrameLength,
    #[error("frame content length is invalid")]
    InvalidContentLength,
}

pub fn frame_content(content: &[u8]) -> Result<Vec<u8>, PackagingError> {
    if content.len() > CONTENT_CAPACITY {
        return Err(PackagingError::ContentTooLarge);
    }

    let mut frame = vec![0_u8; FRAME_SIZE];
    let length = (content.len() as u32).to_be_bytes();
    frame[..4].copy_from_slice(&length);
    frame[4..4 + content.len()].copy_from_slice(content);
    if 4 + content.len() < FRAME_SIZE {
        rand::rngs::OsRng.fill_bytes(&mut frame[4 + content.len()..]);
    }
    Ok(frame)
}

pub fn unframe_content(frame: &[u8]) -> Result<Vec<u8>, PackagingError> {
    if frame.len() != FRAME_SIZE {
        return Err(PackagingError::InvalidFrameLength);
    }

    let content_length = u32::from_be_bytes(frame[..4].try_into().unwrap()) as usize;
    if content_length > CONTENT_CAPACITY {
        return Err(PackagingError::InvalidContentLength);
    }

    Ok(frame[4..4 + content_length].to_vec())
}
