# English phone demos

Recorded on 29 September 2026 with Codex in mobby on a Xiaomi M2007J1SC running Android 13. The phone and mobby were set to English. Prompts and agent replies are in English; Xiaomi store listings and some vendor popups still contain Chinese text.

| Demo | Recording | Result |
| --- | --- | --- |
| Screen Q&A | [Watch](screen-qa.mp4) | The floating chat reads Python's About page and gives an English explanation and a beginner link. |
| Photos and files | [Watch](media-files.mp4) | Copies the demo image, writes an English Markdown summary and CSV list, exports all three files, and reads them back. The original and exported image have the same SHA-256. |
| Install an app and use a feature | [Watch · 8× speed](app-feature-8x.mp4) | Installs Via 7.3.3 from its Xiaomi store page, completes essential first-run setup, and opens Bookmarks. No sign-in or default-browser change. |
| Build and run a useful program | [Watch](travel-checklist.mp4) · [Code](../../examples/travel-checklist-en/) | Codex writes the English checklist and Node server on the phone. HTTP 200 remains available after the task ends; checking, adding, deleting, and persistence after refresh were verified in the phone browser. |

Via's package is `mark.via`; Android reports its first installation and update at 05:44:29 on the recording date, with `com.xiaomi.market` as installer. Google Play is disabled on this device. Earlier VLC and Edge attempts were stopped and are not presented as successful demos; Edge was already installed on the phone.

The app-control video plays at 8× speed. The other recordings play at normal speed. Raw recordings and stopped attempts are kept outside the repository.

Screen operations were observed with screenshots during execution. Running `uiautomator dump` appeared to interrupt the app's accessibility service during an earlier attempt, so it was avoided during active screen tasks. App control still needs supervision: store ads and layout changes can lead to wrong taps or extra navigation.

The [Chinese recordings](../real-device-demos.md) remain separate.

The English checklist runs at `http://127.0.0.1:18792/` on the phone and uses its own localStorage key. During verification, Passport or ID was checked, a second Sunscreen entry was added under Toiletries, and Travel insurance was deleted. After refresh, progress remained 1 of 12, Documents contained two items, and Toiletries contained four. The Chinese checklist and its storage were preserved.
