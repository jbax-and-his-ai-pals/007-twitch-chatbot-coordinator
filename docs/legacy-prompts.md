# Legacy transform prompts

Saved 2026-11-12 when the transform prompt moved to the `replace+examples` variant from the prompt lab
(see `scripts/prompt_lab.py`). To go back, paste a block below into the bot's `prompts.transform` (config.yaml or the GUI).

Placeholders `{UPPER}` / `{lower}` are filled in at runtime.

## neoblampbot (state.json override)

```text
don't add or remove any words, phrases, or sentences, except for when you're explicitly asked to do so; triple check your response string for inaccuracies; your special word is {UPPER}; take this next string i give you and replace only the BEST word or word part with "{UPPER}" to create the funniest output; in addition to replacing whole words, be sure to consider partials; for example, if your word is 'BLAMP', then 'the hamptons' might become 'the blamptons', or instead of 'alcohol', it's 'blampohol'; your response should be only the replacement sentence; here's the string:
```

## neobuttsbot (state.json override)

```text
don't add any new words, phrases, or sentences to the sentence, except if you're replacing a word with "butt"; don't remove any words from the sentence, except if you're replacing that word with "butt" per further instructions; don't prepend a word with butt; don't add butt as a suffix; you're replacing some of the word; take the string i give you and replace only the BEST word or word part with "butt" to create the funniest output; if the string is exceptionally long, you can replace two words or word parts; we're going for real toilet humor here; in addition to replacing whole words, be sure to consider partials; for example, 'calculation' might become 'calcubutt', or instead of 'alcohol', it's 'alcobutt' or maybe 'buttohol'; your response should be only the buttified sentence; here's the string:
```

## toikeybot (state.json override)

```text
(blank in state.json - config.yaml text was in effect)
```

## config.yaml transform blocks as of the switch

```yaml
            transform: >-
                don't add or remove any words, phrases, or sentences, except for when
                you're explicitly asked to do so; triple check your response string for
                inaccuracies; your special word is {UPPER}; take this next string i give
                you and replace only the BEST word or word part with "{UPPER}" to create
                the funniest output; in addition to replacing whole words, be sure to
                consider partials; for example, if your word is 'BLAMP', then 'the
                hamptons' might become 'the blamptons', or instead of 'alcohol', it's
                'blampohol'; your response should be only the replacement sentence;
                here's the string:
            transform: >-
                don't add any new words, phrases, or sentences to the sentence, except if
                you're replacing a word with "butt"; don't remove any words from the
                sentence, except if you're replacing that word with "butt" per further
                instructions; don't prepend a word with butt; don't add butt as a suffix;
                you're replacing some of the word; take the string i give you and replace
                only the BEST word or word part with "butt" to create the funniest
                output; if the string is exceptionally long, you can replace two words or
                word parts; we're going for real toilet humor here; in addition to
                replacing whole words, be sure to consider partials; for example,
                'calculation' might become 'calcubutt', or instead of 'alcohol', it's
                'alcobutt' or maybe 'buttohol'; your response should be only the
                buttified sentence; here's the string:
            transform: >-
                don't add or remove any words, phrases, or sentences, except for when
                you're explicitly asked to do so; triple check your response string for
                inaccuracies; your special word is {UPPER}; take this next string i give
                you and replace only the BEST word or word part with "{UPPER}" to create
                the funniest output; in addition to replacing whole words, be sure to
                consider partials; for example, if your word is 'TOIKEY', then 'the
                hamptons' might become 'the toikeytons', or instead of 'alcohol', it's
                'toikeyhol'; your response should be only the replacement sentence;
                you can also convert ur-sounding words to be oi-sounding, so 'word' is
                'woid', 'bird' is 'boid', 'curb' is 'coib', and so on;
                here's the string:
```
