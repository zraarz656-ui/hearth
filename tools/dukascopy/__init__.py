"""Dukascopy historical data fetcher and converter for TradeQuest.

Standalone offline data-preparation tool. Produces authentic XAUUSD
1-minute CSV datasets for the Phase 1 import pipeline.

This package is deliberately separate from the Android application: the
app is offline-first and imports CSV files, so it needs no network code
and no INTERNET permission.
"""
