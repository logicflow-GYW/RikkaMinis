package com.rikkaminis.app.ui.chat

/**
 * MdText's generation policy. TextLayoutInput equality includes the annotated
 * text, font/style, density, direction and constraints (not merely text length).
 * MultiParagraph identity also catches asynchronous font/placeholder re-layouts
 * with otherwise equal inputs and outer size. Identical re-layouts reuse it.
 *
 * Generic keys keep the policy JVM-testable without replacing Compose's layout
 * engine; the call site passes the real TextLayoutInput/MultiParagraph/IntSize.
 */
fun <Input : Any, Paragraph : Any, Size : Any> uxRenderLayoutNeedsRefresh(
    previousInput: Input?,
    input: Input,
    previousParagraph: Paragraph?,
    paragraph: Paragraph,
    previousSize: Size?,
    size: Size,
): Boolean = previousInput != input || previousParagraph !== paragraph || previousSize != size
