const cors = { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info" };
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { ...cors, "Content-Type": "application/json" } });
Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return json({ error: "METHOD_NOT_ALLOWED" }, 405);
  const base = Deno.env.get("SUPABASE_URL"), key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  const authorization = req.headers.get("authorization");
  if (!base || !key) return json({ error: "SERVER_NOT_CONFIGURED" }, 503);
  if (!authorization?.startsWith("Bearer ")) return json({ error: "NOT_AUTHENTICATED" }, 401);
  const checked = await fetch(`${base}/auth/v1/user`, { headers: { apikey: key, Authorization: authorization } });
  if (!checked.ok) return json({ error: "NOT_AUTHENTICATED" }, 401);
  const user = await checked.json();
  if (!user.id || user.role !== "authenticated") return json({ error: "NOT_AUTHENTICATED" }, 401);
  const admin = { apikey: key, Authorization: `Bearer ${key}`, "Content-Type": "application/json" };
  try {
    // Real objects must be removed through Storage, never by deleting storage.objects SQL rows.
    for (const bucket of ["patient_media", "clinic_assets"]) {
      async function collect(prefix: string): Promise<string[]> {
        const paths: string[] = [];
        let offset = 0;
        while (true) {
          const res = await fetch(`${base}/storage/v1/object/list/${bucket}`, { method: "POST", headers: admin, body: JSON.stringify({ prefix, limit: 1000, offset, sortBy: { column: "name", order: "asc" } }) });
          if (!res.ok) throw Error("STORAGE_LIST_FAILED");
          const list = await res.json();
          for (const entry of list) {
            const path = `${prefix}/${entry.name}`;
            if (!path.startsWith(`${user.id}/`)) throw Error("INVALID_OWNER_PATH");
            if (entry.id) paths.push(path); else paths.push(...await collect(path));
          }
          if (list.length < 1000) break;
          offset += list.length;
        }
        return paths;
      }
      const paths = await collect(user.id);
      for (let i = 0; i < paths.length; i += 100) {
        const deleted = await fetch(`${base}/storage/v1/object/${bucket}`, { method: "DELETE", headers: admin, body: JSON.stringify({ prefixes: paths.slice(i, i + 100) }) });
        if (!deleted.ok) throw Error("STORAGE_DELETE_FAILED");
      }
    }
    const rows = await fetch(`${base}/rest/v1/rpc/delete_my_account`, { method: "POST", headers: { apikey: key, Authorization: authorization, "Content-Type": "application/json" }, body: "{}" });
    if (!rows.ok) throw Error("DATABASE_DELETE_FAILED");
    const deleted = await fetch(`${base}/auth/v1/admin/users/${encodeURIComponent(user.id)}`, { method: "DELETE", headers: admin });
    if (!deleted.ok) throw Error("AUTH_DELETE_FAILED");
    return json({ ok: true });
  } catch {
    return json({ error: "DELETE_INCOMPLETE_RETRY" }, 502);
  }
});
