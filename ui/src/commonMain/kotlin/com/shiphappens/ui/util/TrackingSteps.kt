package com.shiphappens.ui.util

/**
 * Display labels for the 5-step timeline, indexed by `TrackingStatus.stepIndex` /
 * `Parcel.effectiveStepIndex`. Shared by the list row status and the detail timeline so the two
 * screens always name the current step identically.
 */
val TRACKING_STEP_LABELS = listOf("Label created", "Shipped", "In transit", "Out for delivery", "Delivered")
