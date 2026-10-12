//! Plain tables for people: a header, then rows, each column as wide as its widest cell.

/// Lays out `rows` under `header`, columns separated by two spaces, no trailing spaces.
#[must_use]
pub fn table(header: &[&str], rows: &[Vec<String>]) -> String {
    let mut widths: Vec<usize> = header.iter().map(|h| h.chars().count()).collect();
    for row in rows {
        for (width, cell) in widths.iter_mut().zip(row) {
            *width = (*width).max(cell.chars().count());
        }
    }
    let line = |cells: Vec<&str>| -> String {
        let padded: Vec<String> =
            cells.iter().zip(&widths).map(|(cell, width)| format!("{cell:<width$}", width = *width)).collect();
        padded.join("  ").trim_end().to_owned()
    };
    let mut out = vec![line(header.to_vec())];
    out.extend(rows.iter().map(|row| line(row.iter().map(String::as_str).collect())));
    out.join("\n")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn columns_are_as_wide_as_their_widest_cell() {
        let rows = vec![vec!["a".to_owned(), "₹1.00".to_owned()], vec!["long name".to_owned(), "₹10.00".to_owned()]];
        assert_eq!(table(&["NAME", "COST"], &rows), "NAME       COST\na          ₹1.00\nlong name  ₹10.00");
    }

    #[test]
    fn a_table_with_no_rows_is_its_header() {
        assert_eq!(table(&["ID", "STATE"], &[]), "ID  STATE");
    }
}
