package com.arkdo.android.ui.home

import com.arkdo.android.core.model.HomeCategory
import com.arkdo.android.core.model.HomeFeed
import com.arkdo.android.core.model.HomeQuery
import com.arkdo.android.core.model.HomeUser
import com.arkdo.android.data.models.TopicCategory
import com.arkdo.android.data.models.User
import com.arkdo.android.services.topic.TopicListQuery

/** Maps legacy app-owned home state into the stable feature contract. */
fun TopicListQuery.toHomeQuery(): HomeQuery = HomeQuery(
    feed = HomeFeed.entries.firstOrNull { it.apiValue == filter } ?: HomeFeed.LATEST,
    categoryId = category?.id,
    categorySlug = category?.slug,
    categoryName = category?.name,
    tag = tag?.name,
    tagId = tag?.id,
    tagSlug = tag?.slug
)

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

fun TopicCategory.toHomeCategory(): HomeCategory = HomeCategory(
    id = id,
    name = name,
    color = color,
    textColor = textColor,
    parentCategoryId = parentCategoryId,
    slug = slug,
    uploadedLogo = uploadedLogo,
    icon = icon,
    readRestricted = readRestricted == true
)