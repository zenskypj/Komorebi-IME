use std::path::Path;

use komorebi_core::RomajiTable;

fn shipped_table() -> RomajiTable {
    let path = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("..")
        .join("..")
        .join("..")
        .join("app/src/main/assets/zenzai/romaji.tsv");
    RomajiTable::load(&path).expect("shipped romaji table")
}

#[test]
fn converts_readings_with_the_shipped_table() {
    let table = shipped_table();
    let cases = [
        ("きょうはいいてんき", "kyouhaiitenki"),
        ("こんにちは", "konnitiha"),
        ("しんよう", "sin'you"),
        ("がっこう", "gakkou"),
        ("とうきょう", "toukyou"),
        ("まっちゃ", "mattya"),
    ];
    for (reading, expected) in cases {
        assert_eq!(table.to_romaji(reading).unwrap(), expected, "{reading}");
    }
}
