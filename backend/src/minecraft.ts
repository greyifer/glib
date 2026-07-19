import { ApiError, mojangUuid } from "./domain.js";

interface MojangProfile {
  id?: unknown;
  name?: unknown;
}

export interface VerifiedMinecraftProfile {
  playerUuid: string;
  username: string;
}

export async function verifyMinecraftJoin(username: string, serverId: string): Promise<VerifiedMinecraftProfile> {
  const url = new URL("https://sessionserver.mojang.com/session/minecraft/hasJoined");
  url.searchParams.set("username", username);
  url.searchParams.set("serverId", serverId);

  let response: Response;
  try {
    response = await fetch(url, {
      headers: { accept: "application/json", "user-agent": "GlibNetwork/0.1" },
      signal: AbortSignal.timeout(8_000),
    });
  } catch {
    throw new ApiError(503, "AUTH_UPSTREAM_UNAVAILABLE", "Minecraft authentication is temporarily unavailable.");
  }

  if (response.status === 204 || response.status === 404) {
    throw new ApiError(401, "AUTH_PROOF_REJECTED", "Minecraft did not confirm this login challenge.");
  }
  if (!response.ok) {
    throw new ApiError(503, "AUTH_UPSTREAM_UNAVAILABLE", "Minecraft authentication is temporarily unavailable.");
  }

  let profile: MojangProfile;
  try {
    profile = (await response.json()) as MojangProfile;
  } catch {
    throw new ApiError(502, "AUTH_UPSTREAM_INVALID", "Minecraft returned an invalid authentication response.");
  }
  if (typeof profile.name !== "string" || profile.name.toLowerCase() !== username.toLowerCase()) {
    throw new ApiError(401, "AUTH_PROOF_REJECTED", "Minecraft returned a different account.");
  }
  return { playerUuid: mojangUuid(profile.id), username: profile.name };
}
