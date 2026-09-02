package bpm.platform.client

import net.minecraft.client.renderer.texture.TextureAtlasSprite

/**
 * A point across a sprite, as a fraction of its width or height.
 *
 * This is not only a signature change and that is the part worth stating: below 1.20.5 `getU` takes a
 * DOUBLE in the range 0..16 -- the old sixteenths-of-a-block convention -- where the modern one takes a
 * float in 0..1. Passing 0.2f to the old method would ask for a fiftieth of the sprite rather than a
 * fifth, which compiles once the type is right and is silently wrong.
 *
 * So the seam takes the fraction the callers already think in and converts.
 */
//? if >=1.20.5 {
fun spriteU(sprite: TextureAtlasSprite, fraction: Float): Float = sprite.getU(fraction)

fun spriteV(sprite: TextureAtlasSprite, fraction: Float): Float = sprite.getV(fraction)
//?} else {
/*fun spriteU(sprite: TextureAtlasSprite, fraction: Float): Float = sprite.getU(fraction * 16.0)

fun spriteV(sprite: TextureAtlasSprite, fraction: Float): Float = sprite.getV(fraction * 16.0)
*///?}
