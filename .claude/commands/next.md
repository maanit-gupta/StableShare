Continue the StableShare final phases. Context was cleared, so work only from files.

1. Read the "NEXT STEP:" line at the top of PROGRESS.md. If it is missing, the next step is A.
2. In FINAL-PHASES.md, read the GLOBAL RULES and only the section for that step. Do not read other phases or re-read whole docs. Open only the files that step needs.
3. Do that one step completely. Follow its [ASK] and [USER] markers: when you hit one, ask or tell me, then stop and wait in this session.
4. When the step is DONE:
   - Tick it in PROGRESS.md with a one-line note.
   - Set the top line of PROGRESS.md to "NEXT STEP: <id>" using the order below.
   - Commit with a short message.
5. End your reply with exactly:
   "✅ <step> done. Next: <id>. Run /clear, then /next."
   Do not start the next step.

Step order (one step per session):
A → B → C → D → D2 → E1 → E2 → F1 → F2

- E1 = phase E README sections 1–6.
- E2 = phase E sections 7–12 plus the final checks.
- F1 = phase F items 1–3.
- F2 = phase F items 4–5.

Rules for E2 and later: if the step depends on my reply to an [ASK] or [USER] item and I haven't replied yet, ask again and stop. If everything is done, say "Submission complete" and set NEXT STEP: none.