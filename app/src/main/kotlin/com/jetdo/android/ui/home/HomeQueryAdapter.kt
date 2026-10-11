package com.jetdo.android.ui.home

import com.jetdo.android.core.model.HomeUser
import com.jetdo.android.data.models.User

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
