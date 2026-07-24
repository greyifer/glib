import { loadConfig } from "./config.js";
import { createPool, migrate } from "./db.js";
import { parseUuid, validatePassword } from "./domain.js";
import { hashAdminPassword } from "./moderation.js";

const config = loadConfig();
const pool = createPool(config);

try {
  await migrate(pool);
  const action = (process.env.ADMIN_ACTION?.trim().toLowerCase() || "grant");
  const playerUuid = parseUuid(process.env.ADMIN_TARGET_UUID, "ADMIN_TARGET_UUID");

  if (action === "grant") {
    const suppliedPassword = process.env.ADMIN_INITIAL_PASSWORD;
    const pipedPasswords = process.env.ADMIN_PASSWORD_STDIN === "1"
      ? await readPasswordPairFromStdin()
      : undefined;
    const password = validatePassword(
      suppliedPassword ?? pipedPasswords?.[0] ?? await readHidden("Initial password: "),
      "initial password",
    );
    if (suppliedPassword === undefined) {
      const confirmation = pipedPasswords?.[1] ?? await readHidden("Confirm initial password: ");
      if (password !== confirmation) throw new Error("The passwords did not match");
    }
    const role = process.env.ADMIN_ROLE?.trim().toUpperCase() || "MODERATOR";
    if (role !== "MODERATOR" && role !== "OWNER") {
      throw new Error("ADMIN_ROLE must be MODERATOR or OWNER");
    }
    if (!(await pool.query("SELECT 1 FROM users WHERE player_uuid = $1", [playerUuid])).rowCount) {
      throw new Error("That UUID has not signed in to G'Network yet. Have the player open Friends & Chat once, then retry.");
    }
    const passwordHash = await hashAdminPassword(password);
    await pool.query(
      `INSERT INTO admin_accounts(player_uuid, role, password_hash, must_change_password, disabled_at)
       VALUES ($1, $2, $3, true, NULL)
       ON CONFLICT (player_uuid) DO UPDATE SET
         role = EXCLUDED.role,
         password_hash = EXCLUDED.password_hash,
         must_change_password = true,
         disabled_at = NULL,
         failed_attempts = 0,
         locked_until = NULL,
         updated_at = now()`,
      [playerUuid, role, passwordHash],
    );
    await pool.query("DELETE FROM admin_sessions WHERE player_uuid = $1", [playerUuid]);
    process.stdout.write(`Granted ${role} access to ${playerUuid}. The initial password must be changed at first login.\n`);
  } else if (action === "revoke") {
    await pool.query("UPDATE admin_accounts SET disabled_at = now(), updated_at = now() WHERE player_uuid = $1", [playerUuid]);
    await pool.query("DELETE FROM admin_sessions WHERE player_uuid = $1", [playerUuid]);
    process.stdout.write(`Revoked moderator access for ${playerUuid}.\n`);
  } else {
    throw new Error("ADMIN_ACTION must be grant or revoke");
  }
} finally {
  await pool.end();
}

async function readPasswordPairFromStdin(): Promise<readonly [string, string]> {
  if (process.stdin.isTTY) {
    throw new Error("ADMIN_PASSWORD_STDIN requires a redirected stdin pipe.");
  }

  process.stdin.setEncoding("utf8");
  let input = "";
  for await (const chunk of process.stdin) {
    input += chunk;
    if (input.length > 520) throw new Error("The password input exceeded the allowed size.");
  }

  const lines = input.replace(/\r\n/g, "\n").split("\n");
  if (lines.length < 2) throw new Error("The password input was incomplete.");
  return [lines[0] ?? "", lines[1] ?? ""];
}

function readHidden(prompt: string): Promise<string> {
  if (!process.stdin.isTTY || !process.stdout.isTTY || typeof process.stdin.setRawMode !== "function") {
    throw new Error("A TTY is required for the hidden password prompt. Use the G'Network Admin secure-input flow.");
  }
  return new Promise((resolve, reject) => {
    let value = "";
    const cleanup = (): void => {
      process.stdin.off("data", onData);
      process.stdin.setRawMode(false);
      process.stdin.pause();
    };
    const onData = (chunk: Buffer | string): void => {
      for (const character of chunk.toString()) {
        if (character === "\u0003") {
          cleanup();
          process.stdout.write("\n");
          reject(new Error("Cancelled"));
          return;
        }
        if (character === "\r" || character === "\n") {
          cleanup();
          process.stdout.write("\n");
          resolve(value);
          return;
        }
        if (character === "\u0008" || character === "\u007f") {
          if (value.length > 0) {
            value = value.slice(0, -1);
            process.stdout.write("\b \b");
          }
        } else if (character >= " " && value.length < 128) {
          value += character;
          process.stdout.write("*");
        }
      }
    };
    process.stdout.write(prompt);
    process.stdin.setRawMode(true);
    process.stdin.resume();
    process.stdin.on("data", onData);
  });
}
