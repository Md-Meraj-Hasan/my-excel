# Excel-style ribbon update (2026-10-27)

Updated both Web Engine HTML copies to use a compact two-row ribbon: section tabs on the top row and a horizontal tool strip for the active section. Existing tool element IDs and action handlers are retained. Added the Native Excel bridge handler to the backend-served page so the View > Native Excel action works when the Android bridge is available.

Updated the native Kotlin section menu to show compact horizontal, scrollable tool buttons rather than a long vertical list. The existing actions remain connected to their original handlers.

Validation performed: backend pytest suite (24 passed); JavaScript syntax checked for both HTML copies; ZIP integrity verified. Android Gradle/device build was not run in this environment.
