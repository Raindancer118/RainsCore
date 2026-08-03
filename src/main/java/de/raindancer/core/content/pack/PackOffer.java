package de.raindancer.core.content.pack;

import net.kyori.adventure.text.Component;

import java.util.UUID;

/**
 * One pack, offered to one player.
 *
 * <p>Plain data on purpose: what a client is told is a URL, a hash, whether it may refuse, and a
 * line explaining why. Keeping that separate from how it is delivered is what lets every rule about
 * when to send — and when not to send again — be tested without a server.
 *
 * @param id       the pack's identity to the client, derived from the hash so a changed pack is a
 *                 different pack and a cached one is recognised
 * @param url      where to download it
 * @param sha1     what it must hash to; without this the client cannot cache it
 * @param required whether refusing means being disconnected
 * @param prompt   the line shown with the request, or null for the client's own wording
 */
public record PackOffer(UUID id, String url, String sha1, boolean required, Component prompt) {
}
