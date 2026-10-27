package com.mothandgravel.sohva.ui.remote

import androidx.annotation.StringRes
import com.mothandgravel.sohva.R
import com.mothandgravel.sohva.data.LocalNetwork
import com.mothandgravel.sohva.protocol.TvCheck

enum class CheckMark { OK, PROBLEM, NOTE }

/** What a line's button does, when it has one. */
enum class CheckFix { PAIR_AGAIN, SET_UP_FAST_KEYS, TURN_ON_REMOTE_START }

data class CheckLine(@StringRes val title: Int, val mark: CheckMark, @StringRes val detail: Int? = null, val fix: CheckFix? = null)

/**
 * The connection check as lines to show, from the phone outwards: the first problem is the one to
 * fix, so a TV that does not answer at all says only that, not five unknowns after it.
 */
fun checkLines(network: LocalNetwork, check: TvCheck, fastKeysPaired: Boolean): List<CheckLine> = buildList {
    add(
        when (network) {
            LocalNetwork.OK -> CheckLine(R.string.check_phone, CheckMark.OK)
            LocalNetwork.NO_NETWORK -> CheckLine(R.string.check_phone, CheckMark.PROBLEM, R.string.check_phone_offline)
            LocalNetwork.MOBILE_ONLY -> CheckLine(R.string.check_phone, CheckMark.PROBLEM, R.string.check_phone_mobile)
            LocalNetwork.VPN -> CheckLine(R.string.check_phone, CheckMark.PROBLEM, R.string.check_phone_vpn)
        },
    )
    if (!check.answers) {
        add(CheckLine(R.string.check_tv_answers, CheckMark.PROBLEM, R.string.check_tv_silent))
        return@buildList
    }
    add(CheckLine(R.string.check_tv_answers, CheckMark.OK))
    if (!check.ipControl) {
        // The TV is there (its Android TV remote service answers) but its web API is off.
        add(CheckLine(R.string.check_ip_control, CheckMark.PROBLEM, R.string.check_ip_control_off))
    } else {
        add(CheckLine(R.string.check_ip_control, CheckMark.OK))
        when (check.controlRemotely) {
            true -> add(CheckLine(R.string.check_control_remotely, CheckMark.OK))
            false -> add(CheckLine(R.string.check_control_remotely, CheckMark.PROBLEM, R.string.check_control_remotely_off))
            null -> Unit
        }
        when (check.paired) {
            true -> add(CheckLine(R.string.check_paired, CheckMark.OK))
            false -> add(CheckLine(R.string.check_paired, CheckMark.PROBLEM, R.string.check_paired_expired, CheckFix.PAIR_AGAIN))
            null -> Unit
        }
        when (check.remoteStart) {
            true -> add(CheckLine(R.string.check_remote_start, CheckMark.OK))
            false -> add(CheckLine(R.string.check_remote_start, CheckMark.PROBLEM, R.string.check_remote_start_off, CheckFix.TURN_ON_REMOTE_START))
            null -> Unit
        }
    }
    add(
        when {
            !fastKeysPaired -> CheckLine(R.string.check_fast_keys, CheckMark.NOTE, R.string.check_fast_keys_none, CheckFix.SET_UP_FAST_KEYS)
            check.androidTvRemote -> CheckLine(R.string.check_fast_keys, CheckMark.OK)
            else -> CheckLine(R.string.check_fast_keys, CheckMark.PROBLEM, R.string.check_fast_keys_silent)
        },
    )
}
