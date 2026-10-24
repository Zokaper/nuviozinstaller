Last updated: 2026-10-24

| Branch | Version | Current work |
| --- | --- | --- |
| `main` | `3.1.0-alpha.1` | Published for Windows, universal Mac and signed Android; real self-update tests deferred |

## Release and repository split

Release build-only run [40040796201](https://github.com/Zokaper/nuviozinstaller/actions/runs/40040796201)
passed Windows, universal Mac and signed Android at `674b6efb2d7923cd8f281730494879f08cfee630`.
The iPhone signer check also passed at that commit.
[v3.1.0-alpha.1 is published](https://github.com/Zokaper/nuviozinstaller/releases/tag/v3.1.0-alpha.1)
and marked latest, with those exact three CI artifacts plus `SHA256SUMS.txt`. The build-only run's
Publish job remains skipped: its existing artifacts were promoted with GitHub CLI, without a
rebuild. Package contents, Android version `3.1.0-alpha.1` / code `3010001`, the release certificate
(`75a9088d...ca5024e7`) and 16 KB ZIP alignment were verified locally. GitHub's uploaded asset
digests match the local checksums. Curated GitHub notes are in `release-notes/3.1.0-alpha.1.md`;
no in-app changelog was added.

The maintainer authorized publication with
agent-written GitHub release notes, approved merging the mobile split after its iOS test passed,
and deferred rebuilding older self-update test versions. No local compiler builds were restarted.

Nuvio Z's pinned signer build [40041440923](https://github.com/Zokaper/nuvio-z/actions/runs/40041440923),
test IPA [40041441881](https://github.com/Zokaper/nuvio-z/actions/runs/40041441881), and CI passed
at `fb63bbd26147f1621d2550fee1825699aa8ae046`. That split commit is now on mobile `main`.
The mobile app deliberately retains signer pin `c35952297ef1565021f108160ceb99687a447efe`.

## Apple sign-in investigation

Checked upstream on 2026-10-24. Latest published isideload is **0.5.0**, dated October 19;
the installer and mobile pinned signer use **0.4.6**. The
[0.4.6-to-0.5.0 source change](https://github.com/nab138/isideload/compare/8ca39b6203263e539408986e546c42e7c1cc3699...fc7a81a6de678aa707e05399ffafb8be0ad56921)
adds extension provisioning-profile reuse and changes the sideloader API; it does **not** change
Apple authentication. Later unreleased commits fix an example and change the default anisette
server. Nuvio Z supplies its own ordered servers and retains the server that made its saved identity.

[iLoader 2.4.0](https://github.com/nab138/iloader/releases/tag/v2.4.0), released October 22,
changes its default anisette server as a stability workaround. Subsequent reports still include
[-80045](https://github.com/nab138/iloader/issues/1039) (this report concerns SideStore nightly),
and [trusted-device 2FA failure using isideload 0.5.0](https://github.com/nab138/iloader/issues/1044).
[isideload issue 27](https://github.com/nab138/isideload/issues/27) remains open with an account-specific,
manual Windows workaround; it is not a shipped general fix. These reports cover different failure
modes and do not establish a universal outage or the number of affected accounts.

The maintainer reports that sign-in works in the app. No signing pins or stored identities changed;
there is no evidence that adopting 0.5.0 would fix this issue. A future upgrade must deliberately
update desktop and Android's TLS-patched vendor together and pass the signer build before the mobile
pin moves. Do not reset working credentials or pairing data to reproduce other accounts' failures.

## Remaining verification

Real Windows swap/restart, Mac replacement/restart and Android self-update confirmation remain
unverified. The older Windows `3.1.0-alpha.0` test build was stopped for memory; the older Android
build failed on a Windows file lock. The maintainer will test updates when one becomes available.
Automated test results and package checks do not count as these device tests.
