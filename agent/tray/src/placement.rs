//! Where the IdleO window goes (ADR 0061): above the tray icon, the way Windows 11's own panels sit above the taskbar, kept
//! on the screen. Pure: it is given rectangles in physical pixels and returns where the window's top-left corner goes.

/// A rectangle in physical pixels.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Rect {
    /// Left.
    pub x: i32,
    /// Top.
    pub y: i32,
    /// Width.
    pub width: i32,
    /// Height.
    pub height: i32,
}

impl Rect {
    /// A rectangle.
    #[must_use]
    pub fn new(x: i32, y: i32, width: i32, height: i32) -> Self {
        Self { x, y, width, height }
    }

    fn right(self) -> i32 {
        self.x + self.width
    }

    fn bottom(self) -> i32 {
        self.y + self.height
    }

    /// Whether the point is inside.
    #[must_use]
    pub fn contains(self, x: i32, y: i32) -> bool {
        (self.x..self.right()).contains(&x) && (self.y..self.bottom()).contains(&y)
    }
}

/// The gap between the icon and the window, in pixels at 100% scale.
pub const GAP: i32 = 8;

/// The top-left corner for a `window` of this size beside `icon` on `screen`, with `scale` the display's scale (1.0 is 100%).
/// The window sits above the icon with its middle over it; it goes below the icon when the taskbar is at the top, and is
/// slid sideways until it is wholly on the screen.
#[must_use]
pub fn above(icon: Rect, window: (i32, i32), screen: Rect, scale: f64) -> (i32, i32) {
    let (width, height) = window;
    // The gap is pixels at the display's scale, so it looks the same at 200%.
    #[allow(clippy::cast_possible_truncation)] // A few dozen pixels at most.
    let gap = (f64::from(GAP) * scale).round() as i32;
    let x = (icon.x + icon.width / 2 - width / 2).clamp(screen.x, (screen.right() - width).max(screen.x));
    let above = icon.y - height - gap;
    let y = if above >= screen.y { above } else { icon.bottom() + gap };
    (x, y.clamp(screen.y, (screen.bottom() - height).max(screen.y)))
}

/// Where to put the window when the tray icon's place isn't known (it was opened from the Start menu or a toast): the
/// bottom-right corner of the screen, above where the taskbar usually is.
#[must_use]
pub fn corner(window: (i32, i32), screen: Rect, scale: f64) -> (i32, i32) {
    #[allow(clippy::cast_possible_truncation)] // A few hundred pixels at most.
    let margin = (f64::from(GAP) * 6.0 * scale).round() as i32;
    ((screen.right() - window.0 - margin).max(screen.x), (screen.bottom() - window.1 - margin).max(screen.y))
}

#[cfg(test)]
mod tests {
    use super::*;

    const FHD: Rect = Rect { x: 0, y: 0, width: 1920, height: 1080 };
    /// 400 × 600 logical at 150%.
    const WINDOW_150: (i32, i32) = (600, 900);

    #[test]
    fn the_window_sits_above_the_icon_with_its_middle_over_it() {
        // A taskbar along the bottom, the icon near the right end.
        let icon = Rect::new(1700, 1040, 24, 24);
        let (x, y) = above(icon, (400, 600), FHD, 1.0);
        assert_eq!((x + 200, y + 600 + GAP), (1712, 1040), "centred over the icon, a gap above it");
    }

    #[test]
    fn it_never_leaves_the_screen_on_either_side() {
        let (x, _) = above(Rect::new(1900, 1040, 24, 24), (400, 600), FHD, 1.0);
        assert_eq!(x + 400, 1920, "slid left until it fits");
        let (x, _) = above(Rect::new(0, 1040, 24, 24), (400, 600), FHD, 1.0);
        assert_eq!(x, 0);
    }

    #[test]
    fn a_taskbar_along_the_top_puts_it_below_the_icon() {
        let icon = Rect::new(1700, 0, 24, 40);
        let (_, y) = above(icon, (400, 600), FHD, 1.0);
        assert_eq!(y, 40 + GAP);
    }

    #[test]
    fn the_gap_scales_with_the_display() {
        let icon = Rect::new(1700, 1040, 36, 36);
        let (_, y) = above(icon, WINDOW_150, FHD, 1.5);
        assert_eq!(y + 900, 1040 - 12, "8 px at 100% is 12 at 150%");
    }

    #[test]
    fn a_screen_too_small_for_the_window_still_gets_it_at_the_corner() {
        let small = Rect::new(0, 0, 300, 500);
        assert_eq!(above(Rect::new(200, 470, 24, 24), (400, 600), small, 1.0), (0, 0));
    }

    #[test]
    fn a_second_monitor_to_the_left_is_respected() {
        let left = Rect::new(-1920, 0, 1920, 1080);
        let (x, y) = above(Rect::new(-1000, 1040, 24, 24), (400, 600), left, 1.0);
        assert!(left.contains(x, y) && left.contains(x + 399, y + 599));
    }

    #[test]
    fn with_no_icon_to_go_by_it_goes_to_the_bottom_right() {
        let (x, y) = corner((400, 600), FHD, 1.0);
        assert_eq!((x + 400, y + 600), (1920 - 48, 1080 - 48));
    }
}
