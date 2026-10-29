//! K=7 rate-1/3 tail-biting convolutional code and its exact list decoder (Viterbi from every start
//! state). Same generators and state convention as the prototype.

const GENS: [u32; 3] = [0o133, 0o171, 0o165];

fn parity(x: u32) -> u32 {
    x.count_ones() & 1
}

/// Coded bits (0/1), 3 per input bit. Tail-biting: the encoder starts in the state the last six
/// input bits leave it in.
pub fn encode(bits: &[u8]) -> Vec<u8> {
    let n = bits.len();
    let mut state: u32 = (0..6).map(|j| (bits[n - 1 - j] as u32) << (5 - j)).sum();
    let mut out = Vec::with_capacity(3 * n);
    for &b in bits {
        let reg = ((b as u32) << 6) | state;
        for g in GENS {
            out.push(parity(reg & g) as u8);
        }
        state = reg >> 1;
    }
    out
}

struct Trellis {
    /// Predecessor of state ns for choice c.
    ps: [[usize; 64]; 2],
    /// Expected ±1 symbols for (choice, next state).
    sym: [[[f32; 3]; 64]; 2],
}

fn trellis() -> Trellis {
    let mut t = Trellis { ps: [[0; 64]; 2], sym: [[[0.0; 3]; 64]; 2] };
    for c in 0..2 {
        for ns in 0..64 {
            let p = ((ns & 31) << 1) | c;
            t.ps[c][ns] = p;
            let reg = (((ns >> 5) as u32) << 6) | p as u32;
            for (k, g) in GENS.iter().enumerate() {
                t.sym[c][ns][k] = 2.0 * parity(reg & g) as f32 - 1.0;
            }
        }
    }
    t
}

/// soft: 3 values per payload bit, positive = 1. Returns (metric, bits) for all 64 start states,
/// best first (stable, like the prototype's sort).
pub fn list_viterbi(soft: &[f32]) -> Vec<(f32, Vec<u8>)> {
    let t = trellis();
    let nb = soft.len() / 3;
    let mut m = vec![f32::NEG_INFINITY; 64 * 64];
    for s in 0..64 {
        m[s * 64 + s] = 0.0;
    }
    let mut choices = vec![0u8; nb * 64 * 64];
    let mut next = vec![0f32; 64 * 64];
    for i in 0..nb {
        let y = &soft[3 * i..3 * i + 3];
        let mut bm = [[0f32; 64]; 2];
        for c in 0..2 {
            for ns in 0..64 {
                bm[c][ns] = t.sym[c][ns].iter().zip(y).map(|(a, b)| a * b).sum();
            }
        }
        for start in 0..64 {
            let row = &m[start * 64..start * 64 + 64];
            for ns in 0..64 {
                let a = row[t.ps[0][ns]] + bm[0][ns];
                let b = row[t.ps[1][ns]] + bm[1][ns];
                // First maximum wins on ties, like numpy's argmax.
                let (v, c) = if b > a { (b, 1) } else { (a, 0) };
                next[start * 64 + ns] = v;
                choices[(i * 64 + start) * 64 + ns] = c;
            }
        }
        std::mem::swap(&mut m, &mut next);
    }
    let mut out: Vec<(f32, Vec<u8>)> = (0..64)
        .map(|s| {
            let mut state = s;
            let mut bits = vec![0u8; nb];
            for i in (0..nb).rev() {
                bits[i] = (state >> 5) as u8;
                state = t.ps[choices[(i * 64 + s) * 64 + state] as usize][state];
            }
            (m[s * 64 + s], bits)
        })
        .collect();
    out.sort_by(|a, b| b.0.total_cmp(&a.0));
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn bits(seed: u64, n: usize) -> Vec<u8> {
        let mut x = seed;
        (0..n)
            .map(|_| {
                x ^= x << 13;
                x ^= x >> 7;
                x ^= x << 17;
                (x & 1) as u8
            })
            .collect()
    }

    #[test]
    fn decodes_clean_and_noisy() {
        let b = bits(42, 96);
        let soft: Vec<f32> = encode(&b).iter().map(|&c| 2.0 * c as f32 - 1.0).collect();
        assert_eq!(list_viterbi(&soft)[0].1, b);
        // Flip a few coded bits: still the best candidate.
        let mut noisy = soft.clone();
        for i in [3, 50, 101, 177, 250] {
            noisy[i] = -noisy[i] * 0.5;
        }
        assert_eq!(list_viterbi(&noisy)[0].1, b);
    }
}
