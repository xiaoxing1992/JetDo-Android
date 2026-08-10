package com.arkdo.android;

import coil3.ImageLoader;
import dagger.Lazy;
import dagger.MembersInjector;
import dagger.internal.DaggerGenerated;
import dagger.internal.DoubleCheck;
import dagger.internal.InjectedFieldSignature;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import javax.annotation.processing.Generated;

@QualifierMetadata
@DaggerGenerated
@Generated(
    value = "dagger.internal.codegen.ComponentProcessor",
    comments = "https://dagger.dev"
)
@SuppressWarnings({
    "unchecked",
    "rawtypes",
    "KotlinInternal",
    "KotlinInternalInJava",
    "cast",
    "deprecation",
    "nullness:initialization.field.uninitialized"
})
public final class ArkDoApplication_MembersInjector implements MembersInjector<ArkDoApplication> {
  private final Provider<ImageLoader> imageLoaderProvider;

  private ArkDoApplication_MembersInjector(Provider<ImageLoader> imageLoaderProvider) {
    this.imageLoaderProvider = imageLoaderProvider;
  }

  @Override
  public void injectMembers(ArkDoApplication instance) {
    injectImageLoaderProvider(instance, DoubleCheck.lazy(imageLoaderProvider));
  }

  public static MembersInjector<ArkDoApplication> create(
      Provider<ImageLoader> imageLoaderProvider) {
    return new ArkDoApplication_MembersInjector(imageLoaderProvider);
  }

  @InjectedFieldSignature("com.arkdo.android.ArkDoApplication.imageLoaderProvider")
  public static void injectImageLoaderProvider(ArkDoApplication instance,
      Lazy<ImageLoader> imageLoaderProvider) {
    instance.imageLoaderProvider = imageLoaderProvider;
  }
}
