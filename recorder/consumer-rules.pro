# SPDX-FileCopyrightText: 2026 BluffWorks LLC
# SPDX-License-Identifier: GPL-3.0-only
# AGP 9 R8 no longer keeps default constructors implicitly; Room instantiates the generated
# database implementation reflectively (androidx.room3 docs, "minify").
-keep class * extends androidx.room3.RoomDatabase { <init>(); }
