package appeng.rebuild.pattern;

/** Immutable result of staging one legacy pattern provider for a recipe reload. */
public sealed interface PatternProviderRecipeReloadResult
        permits PreparedPatternProviderRecipeReload, PatternProviderRecipeReloadFailure {
}
