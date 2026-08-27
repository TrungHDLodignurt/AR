package vn.apero.armeasure.common.presentation.mvi

/**
 * Marker interfaces for the MVI triple, mirroring `MviContract.kt` in the Apero apps' `core` module
 * so a reader moving between projects meets one vocabulary.
 *
 * Declared locally rather than reused: this module is standalone and cannot depend on another
 * project's `core`. They are `internal`, so a host that already has its own `MviState` never sees a
 * second one.
 */
internal interface MviState

internal interface MviIntent

internal interface MviEffect
