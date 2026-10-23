//! The installer updating itself, the way Nuvio Z updates itself (nuvio-z's AppUpdater): the newest GitHub release
//! of this repository, the file for this platform, and a hidden marker in the notes that makes the update Important
//! (Auto-release writes it when the signing libraries changed, since an older installer may then fail with Apple).
//!
//! The whole app is replaced, not just its core: the core is compiled in, and the app is only a few MB more.
//! Windows swaps the running .exe (Windows allows renaming it) and macOS the .app bundle, then the app restarts.
//! Every download is checked against the release's SHA256SUMS.txt.
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

const LATEST_URL: &str = "https://api.github.com/repos/Zokaper/nuviozinstaller/releases/latest";
/// Only downloads from this repository's releases are installed.
const ALLOWED_DOWNLOADS: &str = "https://github.com/Zokaper/nuviozinstaller/releases/download/";
pub const SIGNING_CHANGED: &str = "<!-- nuviozinstaller:signing-changed -->";
const SUMS: &str = "SHA256SUMS.txt";

/// The release file for this platform; `None` where the desktop app doesn't update itself.
pub fn asset_name() -> Option<&'static str> {
    if cfg!(target_os = "windows") {
        Some("Nuvio-Z-Installer-Windows-x64.zip")
    } else if cfg!(target_os = "macos") {
        Some("Nuvio-Z-Installer-macOS.zip")
    } else {
        None
    }
}

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Available {
    pub current: String,
    pub version: String,
    /// The notes carry the signing-changed marker: this installer may now fail with Apple.
    pub important: bool,
    /// The release notes without their hidden markers.
    pub notes: String,
    #[serde(skip)]
    url: String,
    #[serde(skip)]
    sums_url: String,
    #[serde(skip)]
    name: String,
}

#[derive(Deserialize)]
struct Release {
    tag_name: String,
    #[serde(default)]
    body: Option<String>,
    #[serde(default)]
    draft: bool,
    #[serde(default)]
    assets: Vec<Asset>,
}

#[derive(Deserialize)]
struct Asset {
    name: String,
    browser_download_url: String,
}

/// 3.1.0-alpha.2 as comparable parts: pre-releases sort before their release (alpha < beta < rc < final).
fn parts(version: &str) -> Option<(u32, u32, u32, u32, u32)> {
    let version = version.trim().trim_start_matches('v');
    let (core, pre) = version.split_once('-').map_or((version, None), |(c, p)| (c, Some(p)));
    let mut numbers = core.split('.').map(|n| n.parse::<u32>().ok());
    let (major, minor, patch) = (numbers.next()??, numbers.next()??, numbers.next()??);
    if numbers.next().is_some() {
        return None;
    }
    let (stage, number) = match pre {
        None => (3, 0),
        Some(pre) => {
            let (name, number) = pre.split_once('.').unwrap_or((pre, "0"));
            let stage = match name {
                "alpha" => 0,
                "beta" => 1,
                "rc" => 2,
                _ => return None,
            };
            (stage, number.parse().ok()?)
        }
    };
    Some((major, minor, patch, stage, number))
}

/// Whether `remote` is a newer installer than `local`. Unreadable versions are never newer.
pub fn is_newer(remote: &str, local: &str) -> bool {
    matches!((parts(remote), parts(local)), (Some(r), Some(l)) if r > l)
}

fn strip_markers(body: &str) -> String {
    let mut out = String::new();
    let mut rest = body;
    while let Some(start) = rest.find("<!--") {
        out.push_str(&rest[..start]);
        match rest[start..].find("-->") {
            Some(end) => rest = &rest[start + end + 3..],
            None => {
                rest = "";
            }
        }
    }
    out.push_str(rest);
    out.trim().to_string()
}

fn client() -> Result<reqwest::Client, String> {
    reqwest::Client::builder()
        .user_agent(concat!("nuviozinstaller/", env!("CARGO_PKG_VERSION")))
        .timeout(std::time::Duration::from_secs(30))
        .build()
        .map_err(|e| format!("update: {e}"))
}

/// The newest release, when it is newer than `current` and has a file for this platform.
pub async fn check(current: &str) -> Result<Option<Available>, String> {
    let Some(name) = asset_name() else { return Ok(None) };
    let response = client()?
        .get(LATEST_URL)
        .header("Accept", "application/vnd.github+json")
        .send()
        .await
        .map_err(|e| format!("update: {e}"))?;
    if !response.status().is_success() {
        return Err(format!("update: HTTP {}", response.status()));
    }
    let release: Release = response.json().await.map_err(|e| format!("update: {e}"))?;
    Ok(pick(release, name, current))
}

fn pick(release: Release, name: &str, current: &str) -> Option<Available> {
    if release.draft || !is_newer(&release.tag_name, current) {
        return None;
    }
    let url = |wanted: &str| {
        release.assets.iter().find(|a| a.name == wanted).map(|a| a.browser_download_url.clone())
            .filter(|u| u.starts_with(ALLOWED_DOWNLOADS) && !u.contains(".."))
    };
    let body = release.body.clone().unwrap_or_default();
    Some(Available {
        current: current.to_string(),
        version: release.tag_name.trim_start_matches('v').to_string(),
        important: body.contains(SIGNING_CHANGED),
        notes: strip_markers(&body),
        url: url(name)?,
        sums_url: url(SUMS)?,
        name: name.to_string(),
    })
}

/// The SHA-256 SHA256SUMS.txt lists for `name`.
fn sum_for(sums: &str, name: &str) -> Option<String> {
    sums.lines().find_map(|line| {
        let (hash, file) = line.split_once(char::is_whitespace)?;
        let file = file.trim().trim_start_matches('*');
        (file == name && hash.len() == 64 && hash.chars().all(|c| c.is_ascii_hexdigit())).then(|| hash.to_lowercase())
    })
}

/// Downloads `update`, checks it, and puts it in place of this app. The caller restarts the app afterwards.
pub async fn apply(update: &Available, progress: impl Fn(f32)) -> Result<(), String> {
    let sums = client()?.get(&update.sums_url).send().await.map_err(|e| format!("update: {e}"))?
        .error_for_status().map_err(|e| format!("update: {e}"))?
        .text().await.map_err(|e| format!("update: {e}"))?;
    let sha = sum_for(&sums, &update.name).ok_or("update: the release doesn't list a checksum for this file")?;
    let dir = std::env::temp_dir().join("nuviozinstaller-update");
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).map_err(|e| format!("update: {e}"))?;
    let zip = dir.join(&update.name);
    crate::selfsign::download(&update.url, &zip, Some(&sha), progress).await?;
    let exe = std::env::current_exe().map_err(|e| format!("update: {e}"))?;
    replace(&zip, &dir, &exe)
}

#[cfg(target_os = "windows")]
fn replace(zip: &Path, _dir: &Path, exe: &Path) -> Result<(), String> {
    let file = std::fs::File::open(zip).map_err(|e| format!("update: {e}"))?;
    let mut archive = zip::ZipArchive::new(file).map_err(|e| format!("update: {e}"))?;
    let index = (0..archive.len())
        .find(|&i| archive.by_index(i).is_ok_and(|f| f.name().ends_with(".exe")))
        .ok_or("update: no installer in the download")?;
    let new = exe.with_extension("exe.new");
    let mut entry = archive.by_index(index).map_err(|e| format!("update: {e}"))?;
    let mut out = std::fs::File::create(&new).map_err(|e| format!("update: {}: {e}", new.display()))?;
    std::io::copy(&mut entry, &mut out).map_err(|e| format!("update: {e}"))?;
    drop(out);
    // Windows lets a running .exe be renamed but not overwritten: move it aside, put the new one in its place.
    let old = old_copy(exe);
    let _ = std::fs::remove_file(&old);
    std::fs::rename(exe, &old).map_err(|e| format!("update: {e}"))?;
    if let Err(e) = std::fs::rename(&new, exe) {
        let _ = std::fs::rename(&old, exe);
        return Err(format!("update: {e}"));
    }
    Ok(())
}

#[cfg(target_os = "macos")]
fn replace(zip: &Path, dir: &Path, exe: &Path) -> Result<(), String> {
    use std::process::Command;
    // …/Nuvio Z iOS Installer.app/Contents/MacOS/<binary>
    let bundle = exe.ancestors().nth(3).filter(|b| b.extension().is_some_and(|e| e == "app"))
        .ok_or("update: not running from an app bundle")?.to_path_buf();
    let unpacked = dir.join("unpacked");
    // ditto keeps the bundle's permissions and links, as the release workflow packed it.
    let status = Command::new("/usr/bin/ditto").arg("-x").arg("-k").arg(zip).arg(&unpacked).status()
        .map_err(|e| format!("update: {e}"))?;
    if !status.success() {
        return Err("update: couldn't unpack the download".into());
    }
    let new = std::fs::read_dir(&unpacked).map_err(|e| format!("update: {e}"))?.flatten()
        .map(|e| e.path()).find(|p| p.extension().is_some_and(|e| e == "app"))
        .ok_or("update: no app in the download")?;
    // Copy next to the running bundle first (same volume), then swap the two by renaming.
    let staged = bundle.with_extension("app.new");
    let _ = std::fs::remove_dir_all(&staged);
    let status = Command::new("/usr/bin/ditto").arg(&new).arg(&staged).status().map_err(|e| format!("update: {e}"))?;
    if !status.success() {
        return Err("update: couldn't copy the new app next to this one".into());
    }
    let old = bundle.with_extension("app.old");
    let _ = std::fs::remove_dir_all(&old);
    std::fs::rename(&bundle, &old).map_err(|e| format!("update: {e}"))?;
    if let Err(e) = std::fs::rename(&staged, &bundle) {
        let _ = std::fs::rename(&old, &bundle);
        return Err(format!("update: {e}"));
    }
    let _ = std::fs::remove_dir_all(&old);
    Ok(())
}

#[cfg(not(any(target_os = "windows", target_os = "macos")))]
fn replace(_zip: &Path, _dir: &Path, _exe: &Path) -> Result<(), String> {
    Err("update: this platform doesn't update itself".into())
}

fn old_copy(exe: &Path) -> PathBuf {
    exe.with_extension("exe.old")
}

/// At start-up: the copy a Windows update moved aside (it was still running then).
pub fn clean_up() {
    if cfg!(target_os = "windows") {
        if let Ok(exe) = std::env::current_exe() {
            let _ = std::fs::remove_file(old_copy(&exe));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn compares_versions_like_semver_with_pre_releases_first() {
        assert!(is_newer("v3.1.0-alpha.2", "3.1.0-alpha.1"));
        assert!(is_newer("3.1.0-beta.1", "3.1.0-alpha.9"));
        assert!(is_newer("3.1.0", "3.1.0-rc.3"));
        assert!(is_newer("3.2.0-alpha.0", "3.1.9"));
        assert!(!is_newer("3.1.0-alpha.1", "3.1.0-alpha.1"));
        assert!(!is_newer("3.0.9", "3.1.0-alpha.1"));
        assert!(!is_newer("nightly", "3.1.0"));
        assert!(!is_newer("3.2.0", "garbage"));
    }

    fn release(tag: &str, body: &str, assets: &[&str]) -> Release {
        Release {
            tag_name: tag.into(),
            body: Some(body.into()),
            draft: false,
            assets: assets.iter().map(|n| Asset {
                name: n.to_string(),
                browser_download_url: format!("https://github.com/Zokaper/nuviozinstaller/releases/download/{tag}/{n}"),
            }).collect(),
        }
    }

    #[test]
    fn picks_this_platforms_file_and_reads_the_important_marker() {
        let name = "Nuvio-Z-Installer-Windows-x64.zip";
        let up = pick(release("v3.2.0", &format!("{SIGNING_CHANGED}\nNew signing."), &[name, SUMS]), name, "3.1.0").unwrap();
        assert_eq!(up.version, "3.2.0");
        assert!(up.important);
        assert_eq!(up.notes, "New signing.");
        assert!(!pick(release("v3.2.0", "Fixes.", &[name, SUMS]), name, "3.1.0").unwrap().important);
        // Not newer, no file for this platform, or no checksums: nothing to offer.
        assert!(pick(release("v3.1.0", "", &[name, SUMS]), name, "3.1.0").is_none());
        assert!(pick(release("v3.2.0", "", &["other.zip", SUMS]), name, "3.1.0").is_none());
        assert!(pick(release("v3.2.0", "", &[name]), name, "3.1.0").is_none());
    }

    #[test]
    fn only_this_repositorys_downloads_are_offered() {
        let name = "Nuvio-Z-Installer-Windows-x64.zip";
        let mut r = release("v3.2.0", "", &[name, SUMS]);
        r.assets[0].browser_download_url = "https://example.com/evil.zip".into();
        assert!(pick(r, name, "3.1.0").is_none());
    }

    #[test]
    fn reads_the_checksum_list_the_release_workflow_writes() {
        let a = "a".repeat(64);
        let b = "B".repeat(64);
        let sums = format!("{a}  Nuvio-Z-Installer-Windows-x64.zip\n{b} *Nuvio-Z-Installer-macOS.zip\n");
        assert_eq!(sum_for(&sums, "Nuvio-Z-Installer-Windows-x64.zip"), Some(a));
        assert_eq!(sum_for(&sums, "Nuvio-Z-Installer-macOS.zip"), Some("b".repeat(64)));
        assert_eq!(sum_for(&sums, "Nuvio-Z-Installer-Android.apk"), None);
    }
}
