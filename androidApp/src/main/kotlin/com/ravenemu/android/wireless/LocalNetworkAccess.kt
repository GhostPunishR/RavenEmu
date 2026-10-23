package com.ravenemu.android.wireless

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Accès au réseau local du câble sans fil (découverte DNS-SD et TCP).
 *
 * Android 17 en fait une permission à l'exécution, `ACCESS_LOCAL_NETWORK`,
 * pour les applications qui ciblent son SDK (37). En dessous, la permission
 * INTERNET suffit, et Google demande de ne pas déclarer ni demander la
 * nouvelle : https://developer.android.com/privacy-and-security/local-network-permission
 *
 * Ce chemin ne s'active donc qu'avec un targetSdk de 37 ou plus. La règle du
 * dépôt `LocalNetworkPermissionPolicyTest` exige alors la déclaration dans le
 * manifeste, et l'interdit avant.
 */
internal object LocalNetworkAccess {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    private const val ANDROID_17 = 37

    /** La permission se demande : appareil et application ciblant Android 17 ou plus. */
    fun required(context: Context): Boolean =
        Build.VERSION.SDK_INT >= ANDROID_17 && context.applicationInfo.targetSdkVersion >= ANDROID_17

    fun granted(context: Context): Boolean =
        !required(context) || context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
}
