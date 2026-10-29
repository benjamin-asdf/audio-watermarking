//! The keyed cell layout, exported once from the prototype (engine/bench/export_layout.py) so both
//! implementations use exactly the same pilot and data cells.

use std::sync::OnceLock;

/// A cell with its chip sign: time cell within the block, frequency cell, ±1.
#[derive(Debug, Clone, Copy)]
pub struct Chip {
    pub t: usize,
    pub f: usize,
    pub s: f32,
}

#[derive(Debug)]
pub struct Layout {
    pub n_t: usize,
    pub n_f: usize,
    pub pilot: Vec<Chip>,
    /// One row of chips per coded bit.
    pub data: Vec<Vec<Chip>>,
}

static BYTES: &[u8] = include_bytes!("layout.bin");

fn parse(b: &[u8]) -> Layout {
    assert_eq!(&b[..4], b"EMLY", "layout.bin: bad magic");
    let u16_at = |i: usize| u16::from_le_bytes([b[i], b[i + 1]]) as usize;
    let (n_t, n_f, n_pilot, coded, chips) = (u16_at(4), u16_at(6), u16_at(8), u16_at(10), u16_at(12));
    let mut pos = 14;
    let cells = |pos: &mut usize, n: usize| -> Vec<usize> {
        let v = (0..n).map(|i| u16_at(*pos + 2 * i)).collect();
        *pos += 2 * n;
        v
    };
    let signs = |pos: &mut usize, n: usize| -> Vec<f32> {
        let v = b[*pos..*pos + n].iter().map(|&x| x as i8 as f32).collect();
        *pos += n;
        v
    };
    let pc = cells(&mut pos, n_pilot);
    let ps = signs(&mut pos, n_pilot);
    let dc = cells(&mut pos, coded * chips);
    let ds = signs(&mut pos, coded * chips);
    assert_eq!(pos, b.len(), "layout.bin: trailing bytes");
    let chip = |c: usize, s: f32| Chip { t: c / n_f, f: c % n_f, s };
    Layout {
        n_t,
        n_f,
        pilot: pc.iter().zip(&ps).map(|(&c, &s)| chip(c, s)).collect(),
        data: (0..coded)
            .map(|i| (0..chips).map(|j| chip(dc[i * chips + j], ds[i * chips + j])).collect())
            .collect(),
    }
}

pub fn get() -> &'static Layout {
    static L: OnceLock<Layout> = OnceLock::new();
    L.get_or_init(|| parse(BYTES))
}

#[cfg(test)]
mod tests {
    #[test]
    fn matches_prototype_geometry() {
        let l = super::get();
        assert_eq!((l.n_t, l.n_f, l.pilot.len(), l.data.len(), l.data[0].len()), (256, 47, 3008, 288, 31));
        assert!(l.pilot.iter().all(|c| c.t < 256 && c.f < 47 && c.s.abs() == 1.0));
    }
}
