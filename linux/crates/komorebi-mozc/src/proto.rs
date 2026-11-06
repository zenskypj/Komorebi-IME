include!(concat!(env!("OUT_DIR"), "/mozc.rs"));

pub mod commands {
    include!(concat!(env!("OUT_DIR"), "/mozc.commands.rs"));
}

pub mod config {
    include!(concat!(env!("OUT_DIR"), "/mozc.config.rs"));
}

pub mod converter {
    include!(concat!(env!("OUT_DIR"), "/mozc.converter.rs"));
}

pub mod protocol {
    include!(concat!(env!("OUT_DIR"), "/mozc.protocol.rs"));
}

pub mod renderer {
    include!(concat!(env!("OUT_DIR"), "/mozc.renderer.rs"));
}

pub mod user_dictionary {
    include!(concat!(env!("OUT_DIR"), "/mozc.user_dictionary.rs"));
}
