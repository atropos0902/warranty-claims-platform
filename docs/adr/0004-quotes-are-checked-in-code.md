# 0004 Quotes are checked by code and not by a second model

Status: accepted.

An adjuster who is shown a clause will trust it. A quote that is nearly right is worse than none.

The check compares text. The clause has to be one of those the model was given, and the quote has to be one or more whole sentences of it, character for character. Whole sentences matter: "Wiring damage caused by rodents or other animals is" is an exact match for part of clause 5.9 and says the opposite of it.

When the check fails the draft is dropped, reason included, and the adjuster sees "No supporting clause found". The model is not asked to try again. A retry teaches nothing about why it failed and doubles the cost of the worst claims.

What this does not catch: a real quote from the wrong clause. That is what the evaluation set measures, and why a person decides.
