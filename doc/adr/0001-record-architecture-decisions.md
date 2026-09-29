# 1. Record architecture decisions

Date: 2026-09-24

## Status

Accepted

## Context

This project makes several choices that are expensive to undo, such as how locks are enforced, how data is stored and which platform APIs the app relies on. Future contributors, including AI coding agents working in separate sessions, need to know why those choices were made without having to reconstruct the reasoning from code.

## Decision

We will use Architecture Decision Records, as described by Michael Nygard in "Documenting Architecture Decisions", stored in `doc/adr/` with one decision per record.

## Consequences

The reasoning behind significant choices is kept in version control next to the code and survives across sessions. Writing each record takes a little extra effort, and records must be superseded rather than edited, so the history of a decision is kept even after it changes.
