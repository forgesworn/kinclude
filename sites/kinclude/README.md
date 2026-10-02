# Kinclude — one-page marketing site

Static page in the style of the Kindred site. No build step. The site is
`public/`. Not deployed yet; it is meant for kinclude.app. The copy describes
the intended design; the product is not built.

## Preview

    python3 -m http.server -d sites/kinclude/public 8000

then open http://localhost:8000. Check it at 375px wide (no horizontal scroll)
and at desktop width.

## Files

- `public/index.html` — the page
- `public/styles.css` — the stylesheet
- `public/favicon.svg` — the favicon
