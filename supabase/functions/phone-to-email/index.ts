// Google OAuth is the only supported sign-in method in 7.0.1.
Deno.serve((req: Request) => new Response(JSON.stringify({ error: "GOOGLE_SIGN_IN_REQUIRED" }), {
  status: req.method === "OPTIONS" ? 200 : 410,
  headers: { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info" },
}));
