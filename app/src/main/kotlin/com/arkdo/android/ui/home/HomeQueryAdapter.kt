package com.arkdo.android.ui.home

import com.arkdo.android.core.model.HomeUser
import com.arkdo.android.data.models.User

fun User.toHomeUser(): HomeUser = HomeUser(
    id = id,
    username = username,
    name = name,
    avatarTemplate = avatarTemplate,
    animatedAvatar = animatedAvatar,
    flairUrl = flairUrl,
    flairBgColor = flairBgColor,
    flairColor = flairColor,
    flairName = flairName
)
