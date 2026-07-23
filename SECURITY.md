# Security policy

## Supported versions

Outis is at `0.1.0-alpha01` and has not yet been published. Only the latest version is supported;
there are no backports. Once releases begin, fixes land in a new patch release rather than being
applied to older ones.

## Reporting a vulnerability

Please report privately, not in a public issue.

Use GitHub's private vulnerability reporting on this repository — the **Report a vulnerability**
button under the Security tab. It opens a channel visible only to you and the maintainer.

Outis is maintained by one person, so please set expectations accordingly: an acknowledgement within a
week is realistic, a same-day response is not. If a report is urgent and unacknowledged after a week,
opening a *non-specific* public issue asking the maintainer to check their security inbox is a
reasonable escalation.

There is no bug bounty.

When reporting, the useful details are: which module and version, which platform, whether it needs a
malicious stream or a malicious host application, and a reproduction if you have one.

## What is worth reporting

Outis handles a few things where a defect would matter:

**Credentials in transit.** `MediaItem.headers` and `DrmConfig.licenseRequestHeaders` are how
applications pass auth tokens, and `DrmConfig.licenseRequestInterceptor` receives the raw DRM
challenge. These are handed to the platform engines and to `NSURLSession` / `HttpURLConnection` /
Shaka's networking layer. The SDK does not log them — the only logging call anywhere in `core/src`
records an IMA ad error code and message, nothing else. If you find a path where a header, token,
challenge or licence is written to a log, a crash report or an error message, that is a valid report.

**Untrusted container parsing.** `ChapterExtractor` parses MP4 and Matroska structures from local
files in pure Kotlin, including files an application may have downloaded. It is hardened against
malformed input with explicit caps on sample counts, table entries, title length and thumbnail size,
and every declared count is clamped against the containing box's real length before allocating. A
crafted file that causes unbounded allocation, a hang, or a read outside the intended range is a valid
report.

**DRM error handling.** The FairPlay path treats any HTTP status ≥ 400 from the licence or certificate
endpoint as a failure rather than passing the response body to AVFoundation. A path where an error
response is accepted as a certificate or CKC would be worth reporting.

## What is out of scope

- The security of the underlying engines. Report Media3, AVFoundation and Shaka Player issues to their
  own projects.
- DRM robustness itself. Outis wires up Widevine, PlayReady and FairPlay; the content protection
  guarantees are the CDM's, not this SDK's.
- Anything requiring a modified build of the SDK or a rooted or jailbroken device.
- Application-level decisions such as where an application stores its own tokens.
