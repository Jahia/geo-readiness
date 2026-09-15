---
geo-readiness: patch
---

The dashboard and the drawer now meet WCAG 2.2 AAA on contrast, carry real headings, and announce a running scan to a screen reader. The charts stopped making every data mark a tab stop — a full failure matrix was upwards of three hundred of them — and instead carry their numbers as text inside the table and list structures they already had, so assistive tech reads the figures and the keyboard passes through in one stop. The drawer is a real `<dialog>`, and every control that had no accessible name has one.
