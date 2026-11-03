# Release

1. Merge the release changes into `master` with the required PR checks passing.
2. Run `gh workflow run release.yml --ref master`. The workflow calculates the
   next patch version from the latest stable tag (with `app.version` as a floor).
3. Require the regression suite, public contributor fixtures, private MED17
   corpus, and all three platform package builds to pass. The private corpus
   uses the read-only `ME7_INTERNAL_DEPLOY_KEY` secret to access `me7-internal`.
4. Inspect the draft release assets and smoke-test the packaged application.
   Confirm the release points to the workflow's tested commit and describe any
   remaining platform or fixture limitations in its notes.
5. Publish the checked draft with `gh release edit <tag> --draft=false --latest`.

The workflow creates a draft pinned to its source commit; successful packaging
does not publish it automatically.
