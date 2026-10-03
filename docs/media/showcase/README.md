# Phone and tablet README screenshots

Captured on 3 October 2026 from the installed mobby app with ADB `screencap`:

| Asset | Device | View |
| --- | --- | --- |
| `phone-source.png` | Xiaomi M2007J1SC, Android 13 | New Claude Code conversation, portrait single-column layout |
| `tablet-source.png` | Xiaomi 24091RPADC, Android 16 | New Pi conversation, landscape two-pane layout |

The source screenshots contain no gateway settings or conversation contents. The framed `phone.png` and `tablet.png` are generated with [frame_device.py](../frame_device.py):

```sh
python docs/media/frame_device.py phone docs/media/showcase/phone-source.png docs/media/showcase/phone.png
python docs/media/frame_device.py tablet docs/media/showcase/tablet-source.png docs/media/showcase/tablet.png
```

The frames are presentation graphics; the app UI inside them is from the device screenshots.

`phone-tablet-poster.png` layers both framed screenshots over a drawn background with slight tilt and shadows. Regenerate it with `python docs/media/showcase/render_poster.py` after updating either screenshot. The poster does not alter the UI inside the device frames.
