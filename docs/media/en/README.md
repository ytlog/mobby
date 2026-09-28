# English phone demos

Recorded on 29 September 2026 with Codex in mobby on a Xiaomi M2007J1SC running Android 13. The phone and mobby were set to English. Prompts and agent replies are in English; Xiaomi store listings and some vendor popups still contain Chinese text.

| Demo | Recording | Result |
| --- | --- | --- |
| Screen Q&A | [Watch](screen-qa-edited.mp4) | The floating chat reads Python's About page and gives an English explanation and a beginner link. |
| Photos and files | [Watch](media-files-edited.mp4) | Copies the demo image, writes an English Markdown summary and CSV list, exports all three files, and reads them back. The original and exported image have the same SHA-256. |
| Install an app and use a feature | [Watch](app-feature-edited.mp4) | Installs Via 7.3.3 from its Xiaomi store page, completes essential first-run setup, and opens Bookmarks. No sign-in or default-browser change. |
| Build and run a useful program | [Watch](travel-checklist-edited.mp4) · [Code](../../examples/travel-checklist-en/) | Codex writes the English checklist and Node server on the phone. HTTP 200 remains available after the task ends; checking, adding, deleting, and persistence after refresh were verified in the phone browser. |

Via's package is `mark.via`; Android reports its first installation and update at 05:44:29 on the recording date, with `com.xiaomi.market` as installer. Google Play is disabled on this device. Earlier VLC and Edge attempts were stopped and are not presented as successful demos; Edge was already installed on the phone.

The README embeds looping GIFs, matching the image-based animation approach in the [AionUi README](https://github.com/iOfficeAI/AionUi/blob/main/docs/readme/readme_ch.md). Linked MP4s use the same edit. Static openings, tails, repeated waits, and part of the app-store navigation recovery were removed; retained waits play at 2–12× speed. These are edited demonstrations, not uninterrupted recordings.

| Demo | Original duration | Edited duration |
| --- | --- | --- |
| Screen Q&A | 53 seconds | 14 seconds |
| Photos and files | 132 seconds | 25 seconds |
| App control | 487 seconds | 35 seconds |
| On-device coding | 231 seconds | 46 seconds |

The exact cut points and speeds are in [edits.json](edits.json); [edit_recordings.py](edit_recordings.py) reproduces the MP4 and GIF outputs from the original recordings. Original MP4 assets are also retained in this folder. Raw recordings and stopped attempts are kept outside the repository.

Screen operations were observed with screenshots during execution. Running `uiautomator dump` appeared to interrupt the app's accessibility service during an earlier attempt, so it was avoided during active screen tasks. App control still needs supervision: store ads and layout changes can lead to wrong taps or extra navigation.

The [Chinese recordings](../real-device-demos.md) remain separate.

The English checklist runs at `http://127.0.0.1:18792/` on the phone and uses its own localStorage key. During verification, Passport or ID was checked, a second Sunscreen entry was added under Toiletries, and Travel insurance was deleted. After refresh, progress remained 1 of 12, Documents contained two items, and Toiletries contained four. The Chinese checklist and its storage were preserved.
