---
name: reply-images
description: Show images to the user in a mobby reply, and edit, generate or convert images and photos, including photos the user attached.
---

# Reply Images

The mobby app shows Markdown images written in your reply. Use this whenever the user should see an image: a photo you edited, a chart or picture you generated, or a file already in the workspace.

## Showing an image

1. Save the image inside the current workspace, for example under `images/`. Keep originals; write edited versions under new names.
2. In your reply write `![short description](images/result.png)`, using a path relative to the workspace. Paths under `~/workspace/` or absolute paths inside the workspace also work.
3. Do not put the image inside a code block, and use one Markdown image per picture.
4. Link other deliverables with Markdown, for example `[Report](out/report.pdf)`. mobby lists these workspace files below your reply. Use new file names for subsequent revisions so earlier results remain available.

The app refuses paths outside the workspace, including links that point outside it, and shows the reason instead of the image. PNG, JPEG, WebP and GIF (first frame) display, up to 20 MiB and 16384 pixels per side. An `https://` image appears only after the user taps to load it; `http://` and other schemes are not displayed.

## Photos the user attached

When the user attaches photos, the prompt lists temporary file copies numbered like the images. The app deletes them when the turn ends, so never link them in your reply and do not look for them in a later turn. If the user may ask for more changes, first copy the original into the workspace, for example `images/original.png`. Save each result in the workspace and link that file.

## Tools on this device

Node.js and npm are available. Python and ImageMagick are not installed. Check with `command -v` before relying on any other tool. If an edit needs a library or package that is not installed, tell the user what you would install and why, and install it only after they agree.
