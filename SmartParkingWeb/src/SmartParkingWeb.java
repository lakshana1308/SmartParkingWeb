import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import model.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import java.sql.*;

/**
 * Smart Parking web interface implemented with the Java 17 built-in HTTP server.
 * No JavaScript framework, Node.js, database, or third-party library is required.
 */
public class SmartParkingWeb {
    private static final ParkingService SERVICE = new ParkingService();
    private static final Map<String, UserSession> SESSIONS = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService CLOCK = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "smartpark-clock"); t.setDaemon(true); return t; });
    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "admin123"; // Demo credentials; change before real deployment.
    private static volatile String ADMIN_HISTORY_QUERY = "";

    private static final class UserSession {
        final String role, mobile, name;
        UserSession(String role, String mobile, String name) { this.role = role; this.mobile = mobile; this.name = name; }
        boolean isAdmin() { return "ADMIN".equals(role); }
    }

    public static void main(String[] args) throws IOException {
        int port = 8083;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", SmartParkingWeb::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        SERVICE.tickClock();
        CLOCK.scheduleAtFixedRate(() -> {
            try {
                if (SERVICE.tickClock()) SERVICE.saveDatabase();
            } catch (Exception ex) {
                System.err.println("Background reservation check failed: " + ex.getMessage());
            }
        }, 10, 10, TimeUnit.SECONDS);
        System.out.println("Smart Parking web application is running at http://localhost:" + port);
        System.out.println("Press Ctrl+C to stop the server.");
    }

    private static void handle(HttpExchange exchange) throws IOException {
        SERVICE.tickClock();
        String path = exchange.getRequestURI().getPath();
        Map<String, String> form = new HashMap<>();
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            form = parseForm(body);
        } else if (exchange.getRequestURI().getRawQuery() != null) {
            form = parseForm(exchange.getRequestURI().getRawQuery());
        }

        String token = cookieValue(exchange, "SPARK_SESSION");
        UserSession session = token == null ? null : SESSIONS.get(token);
        if ("/logout".equals(path)) {
            if (token != null) SESSIONS.remove(token);
            exchange.getResponseHeaders().add("Set-Cookie", "SPARK_SESSION=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
            redirect(exchange, "/login"); return;
        }
        String message = "";
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            try {
                if ("/register".equals(path)) {
                    if (session != null) throw new IllegalStateException("You are already signed in.");
                    String name = required(form, "name").trim();
                    String mobile = normalizeMobile(required(form, "mobile"));
                    String vehicleId = normalizeRegistration(required(form, "vehicleId"));
                    String type = required(form, "vehicleType");
                    boolean permit = "yes".equals(form.get("permitValid"));
                    String password = required(form, "password");
                    if (password.length() < 6) throw new IllegalArgumentException("Password must contain at least 6 characters.");
                    SERVICE.registerUser(name, mobile, vehicleId, type, permit, passwordHash(password));
                    token = UUID.randomUUID().toString();
                    session = new UserSession("USER", mobile, name);
                    SESSIONS.put(token, session);
                    exchange.getResponseHeaders().add("Set-Cookie", "SPARK_SESSION=" + token + "; Path=/; HttpOnly; SameSite=Lax");
                    SERVICE.saveDatabase();
                    redirect(exchange, "/"); return;
                } else if ("/login".equals(path)) {
                    String accountType = form.getOrDefault("accountType", "USER");
                    if ("ADMIN".equals(accountType)) {
                        if (!ADMIN_USERNAME.equals(required(form, "username")) || !ADMIN_PASSWORD.equals(required(form, "password")))
                            throw new IllegalArgumentException("Administrator username or password is incorrect.");
                        session = new UserSession("ADMIN", "", "Administrator");
                    } else {
                        String mobile = normalizeMobile(required(form, "mobile"));
                        UserAccount account = SERVICE.users.get(mobile);
                        if (account == null) throw new IllegalArgumentException("No account found for this mobile number. Please register first.");
                        if (!account.passwordHash.equals(passwordHash(required(form, "password")))) throw new IllegalArgumentException("Incorrect password.");
                        session = new UserSession("USER", mobile, account.name);
                    }
                    token = UUID.randomUUID().toString();
                    SESSIONS.put(token, session);
                    exchange.getResponseHeaders().add("Set-Cookie", "SPARK_SESSION=" + token + "; Path=/; HttpOnly; SameSite=Lax");
                    redirect(exchange, "/"); return;
                } else if ("/logout".equals(path)) {
                    if (token != null) SESSIONS.remove(token);
                    exchange.getResponseHeaders().add("Set-Cookie", "SPARK_SESSION=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
                    redirect(exchange, "/login"); return;
                }
                if (session == null) { redirect(exchange, "/login"); return; }
                if (isAdminOnly(path) && !session.isAdmin()) { redirect(exchange, "/"); return; }
                message = processAction(path, form, session);
                SERVICE.saveDatabase();
                // After a successful checkout, send the user directly to the payment portal.
                // The checkout creates a PENDING receipt; payment must be explicitly confirmed there.
                if ("/checkout".equals(path) && !message.startsWith("Error:") && !session.isAdmin()) {
                    redirect(exchange, "/payments");
                    return;
                }
            } catch (IllegalArgumentException | IllegalStateException ex) {
                message = "Error: " + ex.getMessage();
            } catch (Exception ex) {
                message = "Unable to complete operation: " + ex.getMessage();
            }
        }

        if (session == null && !path.equals("/login") && !path.equals("/register")) { redirect(exchange, "/login"); return; }
        if (session != null && (path.equals("/login") || path.equals("/register"))) { redirect(exchange, "/"); return; }
        if (session != null && session.isAdmin() && path.equals("/park")) { redirect(exchange, "/"); return; }
        if (session != null && isAdminOnly(path) && !session.isAdmin()) { redirect(exchange, "/"); return; }
        if (session != null && session.isAdmin() && path.equals("/my-history") && form.containsKey("user")) ADMIN_HISTORY_QUERY = form.getOrDefault("user", "").trim();
        String page = renderPage(path, message, form, session);
        byte[] bytes = page.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private static boolean isAdminOnly(String path) {
        return Set.of("/waiting", "/events", "/vehicle-search", "/analytics", "/reports").contains(path);
    }

    private static String cookieValue(HttpExchange exchange, String name) {
        List<String> cookies = exchange.getRequestHeaders().get("Cookie");
        if (cookies == null) return null;
        for (String line : cookies) for (String part : line.split(";")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length == 2 && pair[0].equals(name)) return pair[1];
        }
        return null;
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(303, -1);
        exchange.close();
    }

    private static String normalizeMobile(String raw) {
        String mobile = raw.replaceAll("[\\s-]", "");
        if (mobile.startsWith("+91")) mobile = mobile.substring(3);
        if (!mobile.matches("[6-9][0-9]{9}")) throw new IllegalArgumentException("Enter a valid 10-digit Indian mobile number.");
        return mobile;
    }


    private static String normalizeRegistration(String raw) {
        String registration = raw.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
        // Common Indian registration format, e.g. TN01AB1234 or TN36S7477.
        if (!registration.matches("[A-Z]{2}[0-9]{1,2}[A-Z]{1,3}[0-9]{1,4}")) {
            throw new IllegalArgumentException("Enter a valid vehicle registration number (for example TN01AB1234). Do not enter the Vehicle ID here.");
        }
        return registration;
    }

    private static String passwordHash(String password) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format("%02x", b));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private static String processAction(String path, Map<String, String> f, UserSession session) {
        switch (path) {
            case "/vehicles":
                if (session.isAdmin()) {
                    SERVICE.registerVehicle(normalizeRegistration(required(f, "vehicleId")), required(f, "vehicleType"), "yes".equals(f.get("permitValid")));
                    return "Vehicle registered successfully.";
                }
                SERVICE.registerOwnedVehicle(session.mobile, normalizeRegistration(required(f, "vehicleId")), required(f, "vehicleType"), "yes".equals(f.get("permitValid")));
                return "Vehicle added to your account successfully.";
            case "/park":
                if (session.isAdmin()) throw new IllegalStateException("Administrators monitor parking; only users can park a vehicle.");
                return SERVICE.parkVehicle(ownedVehicleId(required(f, "vehicleId"), session));
            case "/reservations":
                if (session.isAdmin()) throw new IllegalStateException("Administrators can view reservations but cannot create them.");
                return SERVICE.reserve(ownedVehicleId(required(f, "vehicleId"), session), parseTime(required(f, "startTime")), parseTime(required(f, "endTime")), f.getOrDefault("lotId", "ANY"), f.getOrDefault("junction", "ENTRY"), f.getOrDefault("spaceId", "ANY"), session.mobile);
            case "/cancel": {
                Reservation r = SERVICE.findReservationById(required(f, "reservationId"));
                if (r == null || (!session.isAdmin() && !SERVICE.isOwnedVehicle(r.getVehicleId(), session.mobile))) throw new IllegalArgumentException("Reservation not found for your account.");
                SERVICE.cancelReservation(r.getReservationId()); return "Reservation cancelled.";
            }
            case "/checkin": {
                Reservation r = SERVICE.findReservationById(required(f, "reservationId"));
                if (r == null || (!session.isAdmin() && !SERVICE.isOwnedVehicle(r.getVehicleId(), session.mobile))) throw new IllegalArgumentException("Reservation not found for your account.");
                SERVICE.checkIn(r.getReservationId()); return "Vehicle checked in.";
            }
            case "/checkout": return SERVICE.checkOut(ownedVehicleId(required(f, "vehicleId"), session));
            case "/demo-pay": {
                if (session.isAdmin()) throw new IllegalStateException("Demo payments are available to users only.");
                String receiptId = required(f, "receiptId");
                String method = required(f, "paymentMethod");
                if (!Set.of("DEMO_UPI", "DEMO_CARD", "DEMO_CASH").contains(method)) throw new IllegalArgumentException("Choose a valid demo payment method.");
                PaymentRecord payment = SERVICE.payments.stream().filter(p -> p.id.equalsIgnoreCase(receiptId)).findFirst().orElse(null);
                if (payment == null || !Objects.equals(payment.ownerMobile, session.mobile)) throw new IllegalArgumentException("Payment record was not found for your account.");
                if ("PAID".equalsIgnoreCase(payment.status)) throw new IllegalStateException("This receipt is already marked paid.");
                if (!"complete".equals(f.get("step"))) return "Review the demo payment details below, then complete the simulation.";
                if (!"yes".equals(f.get("confirmDemo"))) throw new IllegalArgumentException("Please confirm the simulated payment to continue.");
                SERVICE.confirmDemoPayment(receiptId, session.mobile, method);
                return "Demo payment completed and recorded. No real money was transferred.";
            }
            case "/extension":
                if (session.isAdmin()) throw new IllegalStateException("The reservation holder must answer the extension request.");
                SERVICE.respondToExtension(required(f, "pendingId"), session.mobile, required(f, "decision"));
                return "Your extension decision has been recorded.";
            case "/exit-confirm":
                if (session.isAdmin()) throw new IllegalStateException("Only the reservation holder can confirm an exit.");
                SERVICE.confirmExit(required(f, "reservationId"), session.mobile);
                return "Your planned exit has been confirmed.";
            case "/vehicle-search":
                if (!session.isAdmin()) throw new IllegalStateException("Administrator access required.");
                return "Vehicle search updated.";
            case "/time":
                if (!session.isAdmin()) throw new IllegalStateException("Administrator access required.");
                throw new IllegalStateException("Time is managed automatically by the computer clock.");
            default: return "Unknown action.";
        }
    }

    private static String ownedVehicleId(String rawId, UserSession session) {
        String id = rawId.trim().toUpperCase(Locale.ROOT);
        if (!session.isAdmin() && !SERVICE.isOwnedVehicle(id, session.mobile)) throw new IllegalArgumentException("That vehicle does not belong to your account.");
        return id;
    }

    private static String renderPage(String path, String message, Map<String, String> form, UserSession session) {
        String active = path.equals("/") ? "dashboard" : path.substring(1);
        StringBuilder h = new StringBuilder(16000);
        h.append("<!doctype html><html lang='en'><head><meta charset='utf-8'>")
         .append("<meta name='viewport' content='width=device-width,initial-scale=1'>");
        // Refresh only read-only overview and reservation listings so expiry/status changes become visible
        // without forcing users to refresh manually. Do not refresh while users are filling forms.
        if (path.equals("/") || path.equals("/dashboard") || path.equals("/reservations")) {
            h.append("<meta http-equiv='refresh' content='15'>");
        }
        h.append("<title>SmartPark | Allocation System</title><style>")
         .append("*{box-sizing:border-box}body{margin:0;background:#f3f6fb;color:#18243a;font:15px/1.5 'Segoe UI',Arial,sans-serif}")
         .append(".layout{display:flex;min-height:100vh}.side{width:235px;background:#12233f;color:#e8efff;padding:26px 16px;flex-shrink:0}.brand{font-size:21px;font-weight:800;margin:0 10px 6px}.sub{color:#aebfdd;font-size:12px;margin:0 10px 30px}.nav a{display:block;color:#d6e1f7;text-decoration:none;padding:11px 13px;border-radius:9px;margin:4px 0}.nav a:hover,.nav a.on{background:#28446e;color:#fff}.main{padding:30px;width:calc(100% - 235px);max-width:1500px;margin:auto}.top{display:flex;justify-content:space-between;align-items:center;gap:16px;flex-wrap:wrap;margin-bottom:22px}h1{font-size:27px;margin:0}h2{font-size:18px;margin:0 0 15px}.muted{color:#71809a}.pill{background:#e5f7ed;color:#147747;padding:6px 10px;border-radius:20px;font-size:12px;font-weight:700}.cards{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:14px;margin-bottom:20px}.card,.panel{background:white;border:1px solid #e2e8f2;border-radius:14px;padding:19px;box-shadow:0 3px 12px #12233f08}.num{font-size:30px;font-weight:800;margin-top:6px}.grid{display:grid;grid-template-columns:1.2fr .8fr;gap:16px}.formgrid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.field{display:flex;flex-direction:column;gap:6px;margin-bottom:12px}label{font-weight:650;font-size:13px}input,select{width:100%;border:1px solid #cfd8e6;border-radius:8px;padding:10px 11px;font:inherit;background:white;color:#18243a}button,.btn{background:#2458a6;color:white;border:0;border-radius:8px;padding:10px 15px;font:inherit;font-weight:700;cursor:pointer;text-decoration:none;display:inline-block}button:hover,.btn:hover{background:#194681}.notice{background:#eaf3ff;border:1px solid #c8defb;padding:12px 15px;border-radius:10px;margin-bottom:18px}.error{background:#fff0ed;border-color:#ffd0c7}.tablewrap{overflow:auto}table{border-collapse:collapse;width:100%;font-size:13px}th,td{text-align:left;padding:11px 10px;border-bottom:1px solid #edf0f6;white-space:nowrap}th{color:#64738c;font-size:11px;text-transform:uppercase;letter-spacing:.05em}.slots{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:8px;border:1px solid #dbe3ef;border-radius:12px;padding:10px;background:#f8faff;overflow:hidden}.slot{position:relative;cursor:help;transition:transform .15s,box-shadow .15s}.slot:hover{transform:translateY(-2px);box-shadow:0 6px 18px #12233f20;z-index:5}.slot[data-tooltip]:hover:after{content:attr(data-tooltip);position:absolute;left:0;bottom:calc(100% + 8px);min-width:230px;max-width:300px;white-space:pre-line;text-align:left;font-weight:500;font-size:12px;line-height:1.55;background:#12233f;color:#fff;padding:12px 14px;border-radius:10px;box-shadow:0 8px 24px #12233f35;z-index:20;pointer-events:none}.slot[data-tooltip]:hover:before{content:'';position:absolute;left:18px;bottom:calc(100% + 2px);border:6px solid transparent;border-top-color:#12233f;z-index:21;pointer-events:none}.slot{min-height:68px;padding:12px;text-align:left;font-weight:800;font-size:13px;border:1px solid #dfe6f1;border-radius:10px;display:flex;flex-direction:column;align-items:flex-start;justify-content:center;gap:5px}.slot small{font-weight:700;margin:0;font-size:11px}.available{background:#e4f7ec;color:#177448}.occupied{background:#ffe9e6;color:#a9382b}.reserved{background:#fff3d7;color:#8d6100}.upcoming{background:#e8e7ff;color:#4b43a8}.conflict{background:#ffe0b2;color:#8a3b00}.section{margin:20px 0 8px;font-weight:800}.lothead{display:grid;grid-template-columns:1fr 1fr;background:#f3f6fb;color:#64738c;border:1px solid #dbe3ef;border-bottom:0;border-radius:10px 10px 0 0;font-size:11px;font-weight:800;text-transform:uppercase;letter-spacing:.04em}.lothead span{padding:8px 14px;border-right:1px solid #dbe3ef}.legend{display:flex;gap:12px;flex-wrap:wrap;margin:12px 0;font-size:12px;color:#64738c}.legend span{display:inline-flex;align-items:center;gap:5px}.dot{width:10px;height:10px;border-radius:3px;display:inline-block}.summary{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px;margin-top:16px}.summary .card{padding:13px}.summary .num{font-size:24px}.foot{font-size:12px;color:#8290a5;margin-top:24px}@media(max-width:900px){.side{width:190px}.main{width:calc(100% - 190px);padding:20px}.cards{grid-template-columns:repeat(2,minmax(0,1fr))}.grid{grid-template-columns:1fr}.summary{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:620px){.layout{display:block}.side{width:100%;padding:14px}.sub{margin-bottom:10px}.nav{display:flex;overflow:auto;gap:4px}.nav a{white-space:nowrap}.main{width:100%;padding:16px}.cards{grid-template-columns:repeat(2,minmax(0,1fr))}.formgrid{grid-template-columns:1fr}.slots{grid-template-columns:repeat(2,minmax(0,1fr))}.lothead{grid-template-columns:1fr 1fr}.summary{grid-template-columns:repeat(2,minmax(0,1fr))}}")
         .append("</style></head><body><div class='layout'><aside class='side'><div class='brand'>▦ SmartPark</div><p class='sub'>SMART PARKING ALLOCATION</p><nav class='nav'>");
        if (session == null) {
            h.append(nav("/login", "Login", path.equals("/login")))
             .append(nav("/register", "Register", path.equals("/register")));
        } else {
            h.append(nav("/", "Overview", path.equals("/") || path.equals("/dashboard")));
            if (session.isAdmin()) {
                h.append(nav("/vehicles", "Vehicles", active.equals("vehicles")))
                 .append(nav("/vehicle-search", "Search vehicle", active.equals("vehicle-search")))
                 .append(nav("/analytics", "Most frequent", active.equals("analytics")))
                 .append(nav("/reports", "Revenue reports", active.equals("reports")))
                 .append(nav("/my-history", "User history", active.equals("my-history")))
                 .append(nav("/reservations", "Reservations", active.equals("reservations")))
                 .append(nav("/waiting", "Waiting list", active.equals("waiting")))
                 .append(nav("/events", "Event history", active.equals("events")));
            } else {
                h.append(nav("/park", "Park now", active.equals("park")))
                 .append(nav("/vehicles", "My vehicles", active.equals("vehicles")))
                 .append(nav("/reservations", "Reserve a slot", active.equals("reservations")))
                 .append(nav("/my-history", "My parking history", active.equals("my-history")))
                 .append(nav("/payments", "Demo payments", active.equals("payments")));
            }
            h.append(nav("/logout", "Sign out", false));
        }
        h.append("</nav></aside><main class='main'><div class='top'><div><h1>")
         .append(escape(titleFor(path))).append("</h1>");
        if (session != null) h.append("<div class='muted'>").append(escape(session.isAdmin() ? "Administrator" : session.name + " · " + session.mobile)).append("</div>");
        h.append("</div></div>");
        if (!message.isBlank()) h.append("<div class='notice ").append(message.startsWith("Error:") ? "error" : "").append("'>").append(escape(message)).append("</div>");

        switch (path) {
            case "/login": renderLogin(h); break;
            case "/register": renderRegister(h); break;
            case "/vehicles": renderVehicles(h, session); break;
            case "/vehicle-search": renderVehicleSearch(h, form); break;
            case "/analytics": renderVehicleAnalytics(h); break;
            case "/reports": renderRevenueReports(h); break;
            case "/my-history": renderUserHistory(h, session); break;
            case "/receipt": renderReceipt(h, form, session); break;
            case "/payments": renderPayments(h, session); break;
            case "/demo-pay": if ("complete".equals(form.get("step"))) renderPayments(h, session); else if (form.containsKey("receiptId") && form.containsKey("paymentMethod")) renderDemoCheckout(h, form, session); else renderPayments(h, session); break;
            case "/park": renderPark(h, session); break;
            case "/reservations": case "/cancel": case "/checkin": case "/checkout": case "/extension": case "/exit-confirm": renderReservations(h, form, session); break;
            case "/waiting": renderWaiting(h); break;
            case "/events": renderEvents(h); break;
            default: if (session != null && !session.isAdmin()) renderUserDashboard(h, session); else renderAdminDashboard(h);
        }
        h.append("</main></div></body></html>");
        return h.toString();
    }

    private static String nav(String url, String label, boolean on) {
        return "<a class='" + (on ? "on" : "") + "' href='" + url + "'>" + label + "</a>";
    }

    private static String titleFor(String path) {
        switch (path) {
            case "/login": return "Sign in";
            case "/register": return "Create account";
            case "/vehicles": return "Vehicle management";
            case "/vehicle-search": return "Search a vehicle";
            case "/analytics": return "Most frequent vehicles";
            case "/reports": return "Revenue reports";
            case "/my-history": return "My parking history";
            case "/receipt": return "Parking receipt";
            case "/payments": return "Demo payment portal";
            case "/park": return "Parking allocation";
            case "/reservations": return "Reservations";
            case "/waiting": return "Waiting list";
            case "/events": return "Event history";
            default: return "Parking overview";
        }
    }

    private static void renderAdminDashboard(StringBuilder h) {
        h.append("<section class='cards'>");
        metric(h, "Total spaces", String.valueOf(SERVICE.totalSpaces()), "Across 3 parking lots");
        metric(h, "Available now", String.valueOf(SERVICE.availableNowSpaces()), "Currently free spaces");
        metric(h, "Occupied", String.valueOf(SERVICE.occupiedSpaces()), "Active parking sessions");
        metric(h, "Reserved now", String.valueOf(SERVICE.reservedNowSpaces()), "Reservations active at current time");
        metric(h, "Upcoming reservations", String.valueOf(SERVICE.upcomingReservationCount()), "Upcoming bookings for today");
        h.append("</section><section class='panel' style='margin-bottom:18px'><h2>Parking occupancy at a glance</h2><p class='muted'>Red = occupied · Yellow = reserved now · Purple = reserved later · Green = available. This summary is shown at the top so you do not need to scroll.</p><div class='legend'><span><i class='dot' style='background:#dc6259'></i>Occupied</span><span><i class='dot' style='background:#f0b83d'></i>Reserved now</span><span><i class='dot' style='background:#8c83e6'></i>Reserved later</span><span><i class='dot' style='background:#36a879'></i>Available</span></div><div style='display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:16px'>");
        for (ParkingLot lot : SERVICE.lots) {
            int total = lot.getSpaces().size();
            int occupied = (int) lot.getSpaces().stream().filter(ParkingSpace::isOccupied).count();
            int reservedNow = SERVICE.reservedNowForLot(lot.getLotId());
            int reservedLater = SERVICE.reservedLaterForLot(lot.getLotId());
            int available = Math.max(0, total - occupied - reservedNow - reservedLater);
            int occupiedPct = total == 0 ? 0 : (int)Math.round(100.0 * occupied / total);
            int reservedNowPct = total == 0 ? 0 : (int)Math.round(100.0 * reservedNow / total);
            int reservedLaterPct = total == 0 ? 0 : (int)Math.round(100.0 * reservedLater / total);
            int availablePct = Math.max(0, 100 - occupiedPct - reservedNowPct - reservedLaterPct);
            h.append("<div style='border:1px solid #e2e8f2;border-radius:12px;padding:14px'><b>").append(escape(lot.getLotId())).append("</b><div style='height:24px;border-radius:8px;overflow:hidden;background:#e8edf5;display:flex;margin:10px 0'><div title='Occupied: ").append(occupied).append("' style='width:").append(occupiedPct).append("%;background:#dc6259'></div><div title='Reserved now: ").append(reservedNow).append("' style='width:").append(reservedNowPct).append("%;background:#f0b83d'></div><div title='Reserved later: ").append(reservedLater).append("' style='width:").append(reservedLaterPct).append("%;background:#8c83e6'></div><div title='Available: ").append(available).append("' style='width:").append(availablePct).append("%;background:#36a879'></div></div><div style='display:flex;justify-content:space-between;gap:8px;flex-wrap:wrap;font-size:13px'><span style='color:#b43d34'>● Occupied: ").append(occupied).append("</span><span style='color:#9a6b00'>● Reserved now: ").append(reservedNow).append("</span><span style='color:#6559b8'>● Reserved later: ").append(reservedLater).append("</span><span style='color:#16845a'>● Available: ").append(available).append("</span></div><small class='muted'>").append(total).append(" total spaces</small></div>");
        }
        h.append("</div></section><div class='grid'><section class='panel'><h2>Parking layout</h2>")
         .append("<div class='legend'><span><i class='dot' style='background:#e4f7ec'></i>Free</span><span><i class='dot' style='background:#ffe9e6'></i>Occupied</span><span><i class='dot' style='background:#fff3d7'></i>Reserved now</span><span><i class='dot' style='background:#e8e7ff'></i>Reserved later</span><span><i class='dot' style='background:#ffe0b2'></i>Occupancy / booking conflict</span><span><b>ACC</b> Accessible space</span></div>");
        for (ParkingLot lot : SERVICE.lots) {
            h.append("<div class='section'>").append(escape(lot.getLotId())).append("</div><div class='lothead'><span>Parking bays · 4 per row</span><span>Status shown by colour</span></div><div class='slots'>");
            List<ParkingSpace> spaces = lot.getSpaces();
            for (int i = 0; i < spaces.size(); i++) {
                ParkingSpace space = spaces.get(i);
                String status = SERVICE.spaceStatus(space);
                h.append("<div class='slot ").append(status.toLowerCase(Locale.ROOT)).append("' data-tooltip='").append(escape(SERVICE.adminSpaceTooltip(space))).append("' title='").append(escape(SERVICE.adminSpaceTooltip(space))).append("'><span>")
                 .append(escape(space.getSpaceId()));
                if (space.getSpaceType().equalsIgnoreCase("ACCESSIBLE")) h.append(" <small style='display:inline-block'>ACC</small>");
                h.append("</span><small>").append(status.equals("AVAILABLE") ? "FREE" : escape(status)).append("</small></div>");
            }
            h.append("</div>");
        }
        h.append("<div class='summary'>");
        metric(h, "Total", String.valueOf(SERVICE.totalSpaces()), "Spaces");
        metric(h, "Occupied", String.valueOf(SERVICE.occupiedSpaces()), "In use");
        metric(h, "Reserved", String.valueOf(SERVICE.reservedNowSpaces()), "Now");
        metric(h, "Available", String.valueOf(SERVICE.availableNowSpaces()), "Free now");
        h.append("</div><hr style='border:0;border-top:1px solid #edf0f6;margin:22px 0'><h2>Upcoming reservation schedule</h2>");
        List<Reservation> upcoming = SERVICE.upcomingReservations();
        if (upcoming.isEmpty()) h.append("<p class='muted'>No upcoming reservations.</p>");
        else {
            h.append("<div class='tablewrap'><table><thead><tr><th>Reservation</th><th>Vehicle</th><th>Lot / space</th><th>Time interval</th><th>Mapping</th></tr></thead><tbody>");
            for (Reservation r : upcoming) {
                String spaceState = SERVICE.spaceStatus(SERVICE.findSpaceForDashboard(r.getSpaceId()));
                h.append("<tr><td>").append(escape(r.getReservationId())).append("</td><td>").append(escape(r.getVehicleId()))
                 .append("</td><td>").append(escape(SERVICE.lotForSpace(r.getSpaceId()))).append(" / ").append(escape(r.getSpaceId()))
                 .append("</td><td>").append(escape(SERVICE.reservationDateText(r))).append(" ").append(SERVICE.timeText(r.getStartTime())).append("–").append(SERVICE.timeText(r.getEndTime()))
                 .append("</td><td><b>").append(spaceState.equals("CONFLICT") ? "CHECK CONFLICT" : "MAPPED: " + escape(spaceState)).append("</b></td></tr>");
            }
            h.append("</tbody></table></div>");
        }
        h.append("</section><section class='panel'><h2>Quick actions</h2>")
         .append("<p><a class='btn' href='/vehicles'>View vehicles</a></p><p><a class='btn' href='/reservations'>View reservations</a></p><hr style='border:0;border-top:1px solid #edf0f6;margin:22px 0'>")
         
         .append("</section></div>");
    }

    private static void renderLogin(StringBuilder h) {
        h.append("<div class='grid'><section class='panel'><h2>User login</h2><form method='post' action='/login'><input type='hidden' name='accountType' value='USER'><div class='field'><label>Registered mobile number</label><input name='mobile' inputmode='numeric' required placeholder='9876543210'></div><div class='field'><label>Password</label><input type='password' name='password' required autocomplete='current-password'></div><button>Sign in as user</button></form><p><a href='/register'>New user? Register here</a></p></section><section class='panel'><h2>Administrator login</h2><form method='post' action='/login'><input type='hidden' name='accountType' value='ADMIN'><div class='field'><label>Username</label><input name='username' required autocomplete='username' placeholder='admin'></div><div class='field'><label>Password</label><input type='password' name='password' required autocomplete='current-password'></div><button>Sign in as administrator</button></form></section></div>");
    }

    private static void renderRegister(StringBuilder h) {
        h.append("<section class='panel' style='max-width:850px'><h2>Register user and vehicle</h2><form method='post' action='/register'><div class='formgrid'><div class='field'><label>Full name</label><input name='name' maxlength='80' required></div><div class='field'><label>Mobile number</label><input name='mobile' inputmode='numeric' required pattern='(\\+91)?[6-9][0-9]{9}' placeholder='9876543210'></div><div class='field'><label>Password (minimum 6 characters)</label><input type='password' name='password' minlength='6' required autocomplete='new-password'></div><div class='field'><label>Vehicle registration number</label><input name='vehicleId' maxlength='10' required pattern='[A-Za-z]{2}[0-9]{1,2}[A-Za-z]{1,3}[0-9]{1,4}' title='Example: TN01AB1234. Enter the registration number, not the Vehicle ID.' placeholder='TN01AB1234'></div><div class='field'><label>Vehicle type</label><select id='vehicleType' name='vehicleType' required onchange='document.getElementById(&quot;permitField&quot;).hidden=this.value!==&quot;ACCESSIBLE&quot;'><option value='CAR'>Car</option><option value='TWO_WHEELER'>Two-wheeler</option><option value='ACCESSIBLE'>Accessible vehicle</option></select></div></div><div class='field' id='permitField' hidden><label><input style='width:auto' type='checkbox' name='permitValid' value='yes'> I have a valid accessible parking permit</label></div><button>Create account</button></form></section>");
    }

    private static void renderUserDashboard(StringBuilder h, UserSession session) {
        Vehicle vehicle = SERVICE.vehicleForUser(session.mobile);
        int allAvailable = SERVICE.availableSpaceCountForType("ALL");
        int carAvailable = SERVICE.availableSpaceCountForType("CAR");
        int bikeAvailable = SERVICE.availableSpaceCountForType("TWO_WHEELER");
        int accessibleAvailable = SERVICE.availableSpaceCountForType("ACCESSIBLE");
        h.append("<section class='cards'>");
        metric(h, "All available spaces", String.valueOf(allAvailable), "Across car, two-wheeler and accessible bays");
        metric(h, "Car spaces", String.valueOf(carAvailable), "Available now");
        metric(h, "Two-wheeler spaces", String.valueOf(bikeAvailable), "Available now");
        metric(h, "Accessible spaces", String.valueOf(accessibleAvailable), "Available now");
        metric(h, "Active reservations", String.valueOf(SERVICE.activeReservationsForUserCount(session.mobile)), "Active and checked-in bookings; cancelled, expired and completed bookings are excluded");
        metric(h, "My vehicle", vehicle == null ? "—" : vehicle.getVehicleId(), vehicle == null ? "No vehicle registered" : vehicle.getVehicleType());
        h.append("</section><section class='panel' style='margin:0 0 18px'><h2>Live parking overview</h2><p class='muted'>Red = occupied · Yellow = reserved now · Purple = reserved later · Green = available right now.</p><div class='legend'><span><i class='dot' style='background:#dc6259'></i>Occupied</span><span><i class='dot' style='background:#f0b83d'></i>Reserved now</span><span><i class='dot' style='background:#8c83e6'></i>Reserved later</span><span><i class='dot' style='background:#36a879'></i>Available</span></div><div style='display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:16px'>");
        for (ParkingLot lot : SERVICE.lots) {
            int total = lot.getSpaces().size();
            int occupiedCount = (int) lot.getSpaces().stream().filter(ParkingSpace::isOccupied).count();
            int reservedNow = SERVICE.reservedNowForLot(lot.getLotId());
            int reservedLater = SERVICE.reservedLaterForLot(lot.getLotId());
            int availableCount = Math.max(0, total - occupiedCount - reservedNow - reservedLater);
            int occupiedPct = total == 0 ? 0 : (int)Math.round(100.0 * occupiedCount / total);
            int reservedNowPct = total == 0 ? 0 : (int)Math.round(100.0 * reservedNow / total);
            int reservedLaterPct = total == 0 ? 0 : (int)Math.round(100.0 * reservedLater / total);
            int availablePct = Math.max(0, 100 - occupiedPct - reservedNowPct - reservedLaterPct);
            h.append("<div style='border:1px solid #e2e8f2;border-radius:12px;padding:14px'><b>").append(escape(lot.getLotId())).append("</b><div style='height:24px;border-radius:8px;overflow:hidden;background:#e8edf5;display:flex;margin:10px 0'><div title='Occupied: ").append(occupiedCount).append("' style='width:").append(occupiedPct).append("%;background:#dc6259'></div><div title='Reserved now: ").append(reservedNow).append("' style='width:").append(reservedNowPct).append("%;background:#f0b83d'></div><div title='Reserved later: ").append(reservedLater).append("' style='width:").append(reservedLaterPct).append("%;background:#8c83e6'></div><div title='Available: ").append(availableCount).append("' style='width:").append(availablePct).append("%;background:#36a879'></div></div><div style='display:flex;justify-content:space-between;gap:8px;font-size:13px;flex-wrap:wrap'><span style='color:#b43d34'>● Occupied: ").append(occupiedCount).append("</span><span style='color:#9a6b00'>● Reserved now: ").append(reservedNow).append("</span><span style='color:#6559b8'>● Reserved later: ").append(reservedLater).append("</span><span style='color:#16845a'>● Available: ").append(availableCount).append("</span></div><small class='muted'>").append(total).append(" total spaces</small></div>");
        }
        h.append("</div></section><div class='grid'><section class='panel'><h2>Parking availability</h2><div class='legend'><span><i class='dot' style='background:#e4f7ec'></i>Free</span><span><i class='dot' style='background:#ffe9e6'></i>Occupied</span><span><i class='dot' style='background:#fff3d7'></i>Reserved now</span><span><i class='dot' style='background:#e8e7ff'></i>Reserved later</span></div>");
        for (ParkingLot lot : SERVICE.lots) {
            h.append("<div class='section'>").append(escape(lot.getLotId())).append("</div><div class='lothead'><span>Parking bays · 4 per row</span><span>Status shown by colour</span></div><div class='slots'>");
            for (ParkingSpace space : lot.getSpaces()) {
                String status = SERVICE.spaceStatus(space);
                h.append("<div class='slot ").append(status.toLowerCase(Locale.ROOT)).append("'><span>").append(escape(space.getSpaceId()));
                if (space.getSpaceType().equalsIgnoreCase("ACCESSIBLE")) h.append(" <small style='display:inline-block'>ACC</small>");
                h.append("</span><small>").append(status.equals("AVAILABLE") ? "FREE" : escape(status)).append("</small></div>");
            }
            h.append("</div>");
        }
        h.append("</section><section class='panel'><h2>My notifications</h2>");
        List<Reservation> own = SERVICE.reservationsForUser(session.mobile);
        List<AppNotification> userNotifications = SERVICE.notificationsFor(session.mobile);
        if (userNotifications.isEmpty()) h.append("<p>No new notifications.</p>");
        else {
            for (AppNotification n : userNotifications) {
                h.append("<div class='notice'>").append(escape(n.text));
                if (n.pendingId != null && SERVICE.pendingExtensions.containsKey(n.pendingId)) {
                    h.append("<div style='display:flex;gap:8px;flex-wrap:wrap;margin-top:10px'><form method='post' action='/extension'><input type='hidden' name='pendingId' value='").append(escape(n.pendingId)).append("'><input type='hidden' name='decision' value='extend'><button>Extend until ").append(SERVICE.timeText(SERVICE.pendingExtensions.get(n.pendingId).end)).append("</button></form><form method='post' action='/extension'><input type='hidden' name='pendingId' value='").append(escape(n.pendingId)).append("'><input type='hidden' name='decision' value='decline'><button>Decline extension</button></form></div>");
                } else if (n.text.startsWith("Reminder: reservation ")) {
                    for (Reservation reminderReservation : own) {
                        if (n.text.startsWith("Reminder: reservation " + reminderReservation.getReservationId() + " ")) {
                            h.append("<form method='post' action='/exit-confirm' style='margin-top:10px'><input type='hidden' name='reservationId' value='").append(escape(reminderReservation.getReservationId())).append("'><button>Confirm exit at ").append(SERVICE.timeText(reminderReservation.getEndTime())).append("</button></form>");
                            break;
                        }
                    }
                }
                h.append("</div>");
            }
        }
        h.append("<h2>My reservations</h2>");
        if (own.isEmpty()) h.append("<p>No reservations yet.</p>");
        else {
            h.append("<div class='tablewrap'><table><thead><tr><th>Reservation</th><th>Space</th><th>Time</th><th>Status</th></tr></thead><tbody>");
            for (Reservation r : own) h.append("<tr><td>").append(escape(r.getReservationId())).append("</td><td>").append(escape(r.getSpaceId())).append("</td><td>").append(escape(SERVICE.reservationDateText(r))).append(" ").append(SERVICE.timeText(r.getStartTime())).append("–").append(SERVICE.timeText(r.getEndTime())).append("</td><td>").append(escape(r.getStatus())).append("</td></tr>");
            h.append("</tbody></table></div>");
        }
        h.append("<p><a class='btn' href='/reservations'>Reserve a slot</a></p></section></div>");
    }

    private static void metric(StringBuilder h, String label, String value, String note) {
        h.append("<div class='card'><div class='muted'>").append(label).append("</div><div class='num'>").append(value).append("</div></div>");
    }

    private static void renderVehicles(StringBuilder h, UserSession session) {
        if (!session.isAdmin()) {
            h.append("<section class='panel' style='max-width:900px'><h2>Add another vehicle</h2><p class='muted'>Each vehicle gets its own permanent Vehicle ID (for example VH000001). A single account can register multiple vehicles.</p><form method='post' action='/vehicles'><div class='formgrid'><div class='field'><label>Vehicle registration number</label><input name='vehicleId' maxlength='10' required pattern='[A-Za-z]{2}[0-9]{1,2}[A-Za-z]{1,3}[0-9]{1,4}' title='Example: TN01AB1234. Enter the registration number, not the Vehicle ID.' placeholder='TN01AB1234'></div><div class='field'><label>Vehicle type</label><select name='vehicleType' required><option value='CAR'>Car</option><option value='TWO_WHEELER'>Two-wheeler</option><option value='ACCESSIBLE'>Accessible vehicle</option></select></div></div><div class='field'><label><input style='width:auto' type='checkbox' name='permitValid' value='yes'> Valid accessible parking permit (required for accessible vehicles)</label></div><button>Add vehicle</button></form></section><br>");
            h.append("<section class='panel'><h2>My registered vehicles</h2>");
            List<Vehicle> own = SERVICE.vehiclesForUser(session.mobile);
            if (own.isEmpty()) h.append("<p>No vehicles registered yet.</p>");
            else { h.append("<div class='tablewrap'><table><thead><tr><th>Vehicle ID</th><th>Registration number</th><th>Type</th><th>Permit</th><th>Parking status</th></tr></thead><tbody>");
                for (Vehicle v : own) { ParkingSpace ps=SERVICE.parkedVehicles.get(v.getVehicleId()); h.append("<tr><td><b>").append(escape(SERVICE.primaryIdFor(v.getVehicleId()))).append("</b></td><td>").append(escape(v.getVehicleId())).append("</td><td>").append(escape(v.getVehicleType())).append("</td><td>").append(v.isPermitValid()?"Valid":(v.isPermitRequired()?"Missing":"Not required")).append("</td><td>").append(ps==null?"Not parked":escape(ps.getSpaceId())).append("</td></tr>"); }
                h.append("</tbody></table></div>"); }
            h.append("</section>"); return;
        }
        h.append("<section class='panel'><h2>All registered vehicles</h2>");
        if (SERVICE.vehicles.isEmpty()) h.append("<p>No vehicles registered yet.</p>");
        else { h.append("<div class='tablewrap'><table><thead><tr><th>Primary Vehicle ID</th><th>Owner mobile</th><th>Registration number</th><th>Type</th><th>Permit valid</th><th>Parking</th></tr></thead><tbody>");
            for (Vehicle v : SERVICE.vehicles.values()) { ParkingSpace ps=SERVICE.parkedVehicles.get(v.getVehicleId()); String owner=SERVICE.vehicleOwners.getOrDefault(v.getVehicleId(), "Legacy/admin");
                h.append("<tr><td><b>").append(escape(SERVICE.primaryIdFor(v.getVehicleId()))).append("</b></td><td>").append(escape(owner)).append("</td><td>").append(escape(v.getVehicleId())).append("</td><td>").append(escape(v.getVehicleType())).append("</td><td>").append(v.isPermitValid()?"Yes":(v.isPermitRequired()?"No":"Not required")).append("</td><td>").append(ps==null?"Not parked":escape(ps.getSpaceId())).append("</td></tr>"); }
            h.append("</tbody></table></div>"); }
        h.append("</section>");
    }

    private static void renderVehicleSearch(StringBuilder h, Map<String,String> form) {
        String query = form.getOrDefault("q", "").trim();
        h.append("<section class='panel'><h2>Find a vehicle</h2><p class='muted'>Search by vehicle primary ID, registration number, owner name, mobile number, or vehicle type.</p><form method='get' action='/vehicle-search'><div class='field'><label>Search</label><input name='q' value='").append(escape(query)).append("' placeholder='VH000001 or TN01AB1234'></div><button>Search</button></form></section><br><section class='panel'><h2>Search results</h2>");
        List<Vehicle> matches = new ArrayList<>();
        if (!query.isBlank()) { String q=query.toLowerCase(Locale.ROOT); for (Vehicle v:SERVICE.vehicles.values()) { String owner=SERVICE.vehicleOwners.getOrDefault(v.getVehicleId(), ""); String ownerName=SERVICE.users.containsKey(owner)?SERVICE.users.get(owner).name:""; if (SERVICE.primaryIdFor(v.getVehicleId()).toLowerCase(Locale.ROOT).contains(q) || v.getVehicleId().toLowerCase(Locale.ROOT).contains(q) || v.getVehicleType().toLowerCase(Locale.ROOT).contains(q) || owner.toLowerCase(Locale.ROOT).contains(q) || ownerName.toLowerCase(Locale.ROOT).contains(q)) matches.add(v); } }
        if (query.isBlank()) h.append("<p>Enter a search term to find a vehicle.</p>");
        else if (matches.isEmpty()) h.append("<p>No matching vehicle found.</p>");
        else { h.append("<div class='tablewrap'><table><thead><tr><th>Vehicle ID</th><th>Registration number</th><th>Owner mobile</th><th>Type</th><th>Visits</th><th>Current status</th></tr></thead><tbody>"); for(Vehicle v:matches) { ParkingSpace ps=SERVICE.parkedVehicles.get(v.getVehicleId()); h.append("<tr><td><b>").append(escape(SERVICE.primaryIdFor(v.getVehicleId()))).append("</b></td><td>").append(escape(v.getVehicleId())).append("</td><td>").append(escape(SERVICE.vehicleOwners.getOrDefault(v.getVehicleId(), "—"))).append("</td><td>").append(escape(v.getVehicleType())).append("</td><td>").append(SERVICE.visitCount(v.getVehicleId())).append("</td><td>").append(ps==null?"Not parked":"Parked at "+escape(ps.getSpaceId())).append("</td></tr>"); } h.append("</tbody></table></div>"); }
        h.append("</section>");
    }

    private static void renderVehicleAnalytics(StringBuilder h) {
        List<Vehicle> ranked=new ArrayList<>(SERVICE.vehicles.values()); ranked.sort((a,b)->Integer.compare(SERVICE.visitCount(b.getVehicleId()),SERVICE.visitCount(a.getVehicleId())));
        int max=ranked.stream().mapToInt(v->SERVICE.visitCount(v.getVehicleId())).max().orElse(0);
        h.append("<section class='panel'><h2>Vehicles that use the parking lot most often</h2><p class='muted'>Ranked by successful parking entries recorded by this version. Counts increase when a vehicle parks or checks in. New counts are stored in SQLite.</p>");
        if(ranked.isEmpty()) h.append("<p>No registered vehicles yet.</p>"); else { h.append("<div style='display:grid;gap:14px'>"); int rank=1; for(Vehicle v:ranked) { int count=SERVICE.visitCount(v.getVehicleId()); int width=max==0?0:(int)Math.round(100.0*count/max); h.append("<div><div style='display:flex;justify-content:space-between;gap:10px;flex-wrap:wrap'><b>").append(rank++).append(". ").append(escape(SERVICE.primaryIdFor(v.getVehicleId()))).append(" · ").append(escape(v.getVehicleId())).append("</b><span>").append(count).append(count==1?" visit":" visits").append("</span></div><div style='height:14px;background:#e8edf5;border-radius:8px;overflow:hidden;margin-top:5px'><div style='height:100%;width:").append(width).append("%;background:#2d69bf;border-radius:8px'></div></div><small class='muted'>").append(escape(v.getVehicleType())).append(" · owner ").append(escape(SERVICE.vehicleOwners.getOrDefault(v.getVehicleId(), "Legacy/admin"))).append("</small></div>"); } h.append("</div>"); }
        h.append("<p class='muted' style='margin-top:18px'>This bar chart updates from the system's recorded visits; ties share the same count.</p></section>");
    }

    private static void renderPark(StringBuilder h, UserSession session) {
        Vehicle ownVehicle = session.isAdmin() ? null : SERVICE.vehicleForUser(session.mobile);
        h.append("<div class='grid'><section class='panel'><h2>")
         .append(session.isAdmin() ? "Allocate nearest eligible space" : "On-the-spot parking")
         .append("</h2>");
        if (!session.isAdmin() && ownVehicle == null) {
            h.append("<p>No vehicle is linked to your account. Please register an account with your vehicle first.</p>")
             .append("<p><a class='btn' href='/register'>Register</a></p>");
        } else {
            h.append("<form method='post' action='/park'><div class='field'><label>")
             .append(session.isAdmin() ? "Choose vehicle" : "Your registered vehicle")
             .append("</label><select name='vehicleId' required>");
            if (session.isAdmin()) {
                for (Vehicle v : SERVICE.vehicles.values()) {
                    h.append("<option value='").append(escape(v.getVehicleId())).append("'>")
                     .append(escape(v.getVehicleId())).append(" · ").append(escape(v.getVehicleType())).append("</option>");
                }
            } else {
                for (Vehicle v : SERVICE.vehiclesForUser(session.mobile)) {
                    h.append("<option value='").append(escape(v.getVehicleId())).append("'")
                     .append(v.getVehicleId().equals(ownVehicle.getVehicleId()) ? " selected" : "").append(">")
                     .append(escape(SERVICE.primaryIdFor(v.getVehicleId()))).append(" · ")
                     .append(escape(v.getVehicleId())).append(" · ").append(escape(v.getVehicleType())).append("</option>");
                }
            }
            h.append("</select></div><button ").append(SERVICE.vehicles.isEmpty() ? "disabled" : "")
             .append(">Park now</button></form>");
        }
        h.append("</section><section class='panel'><h2>")
         .append(session.isAdmin() ? "Currently parked vehicles" : "Your parking status")
         .append("</h2>");
        List<Map.Entry<String, ParkingSpace>> parkedToShow = new ArrayList<>();
        for (Map.Entry<String, ParkingSpace> entry : SERVICE.parkedVehicles.entrySet()) {
            if (session.isAdmin() || SERVICE.isOwnedVehicle(entry.getKey(), session.mobile)) parkedToShow.add(entry);
        }
        if (parkedToShow.isEmpty()) {
            h.append(session.isAdmin() ? "<p>No vehicles are currently parked.</p>" : "<p>Your vehicle is not currently parked.</p>");
        } else {
            h.append("<div class='tablewrap'><table><thead><tr><th>Vehicle</th><th>Space</th><th>Action</th></tr></thead><tbody>");
            for (Map.Entry<String, ParkingSpace> e : parkedToShow) {
                h.append("<tr><td>").append(escape(e.getKey())).append("</td><td>")
                 .append(escape(e.getValue().getSpaceId())).append("</td><td><form method='post' action='/checkout'><input type='hidden' name='vehicleId' value='")
                 .append(escape(e.getKey())).append("'><button>Check out</button></form></td></tr>");
            }
            h.append("</tbody></table></div>");
        }
        h.append("</section></div>");
    }

    private static void renderReservations(StringBuilder h, Map<String, String> form, UserSession session) {
        String selectedJunction = form.getOrDefault("junction", "ENTRY");
        String selectedLot = form.getOrDefault("lotId", "ANY");
        if (!SERVICE.junctions().contains(selectedJunction)) selectedJunction = "ENTRY";
        boolean knownLot = selectedLot.equals("ANY");
        for (ParkingLot lot : SERVICE.lots) if (lot.getLotId().equals(selectedLot)) knownLot = true;
        if (!knownLot) selectedLot = "ANY";

        if (!session.isAdmin()) {
        h.append("<div class='grid'><section class='panel'><h2>Create reservation</h2>")
         .append("<form method='post' action='/reservations'><div class='field'><label>Vehicle</label><select name='vehicleId' required onchange='this.form.dataset.vehicle=this.value'>");
        String selectedVehicle = form.getOrDefault("vehicleId", session.isAdmin() ? (SERVICE.vehicles.isEmpty() ? "" : SERVICE.vehicles.keySet().iterator().next()) : SERVICE.firstVehicleIdForUser(session.mobile));
        for (Vehicle v : SERVICE.vehicles.values()) {
            if (!session.isAdmin() && !SERVICE.isOwnedVehicle(v.getVehicleId(), session.mobile)) continue;
            h.append("<option value='").append(escape(v.getVehicleId())).append("'").append(v.getVehicleId().equals(selectedVehicle) ? " selected" : "").append(">")
             .append(escape(v.getVehicleId())).append(" · ").append(escape(v.getVehicleType())).append("</option>");
        }
        h.append("</select></div><div class='formgrid'><div class='field'><label>Start (HH:MM)</label><input name='startTime' placeholder='09:00' required pattern='([01]?[0-9]|2[0-3]):[0-5][0-9]'></div>")
         .append("<div class='field'><label>End (HH:MM)</label><input name='endTime' placeholder='10:00' required pattern='([01]?[0-9]|2[0-3]):[0-5][0-9]'></div></div>")
         .append("<div class='field'><label>Choose preferred parking lot</label><select name='lotId'>")
         .append("<option value='ANY' ").append(selectedLot.equals("ANY") ? "selected" : "").append(">Any eligible lot (nearest route)</option>");
        for (ParkingLot lot : SERVICE.lots) {
            h.append("<option value='").append(escape(lot.getLotId())).append("' ")
             .append(selectedLot.equals(lot.getLotId()) ? "selected" : "").append(">")
             .append(escape(lot.getLotId())).append("</option>");
        }
        h.append("</select></div><div class='field'><label>Your current road junction / entry point</label><select name='junction'>");
        for (String junction : SERVICE.junctions()) {
            h.append("<option value='").append(escape(junction)).append("' ")
             .append(selectedJunction.equals(junction) ? "selected" : "").append(">")
             .append(escape(junction.replace('-', ' '))).append("</option>");
        }
        h.append("</select></div><div class='field'><label>Choose a parking space (optional)</label><select name='spaceId'><option value='ANY' ").append(form.getOrDefault("spaceId", "ANY").equals("ANY") ? "selected" : "").append(">Automatically select nearest eligible space</option>");
        String vehicleForOptions = selectedVehicle.trim().toUpperCase(Locale.ROOT);
        int optionStart = 9 * 60, optionEnd = 10 * 60;
        try { if (!form.getOrDefault("startTime", "").isBlank()) optionStart = parseTime(form.get("startTime")); if (!form.getOrDefault("endTime", "").isBlank()) optionEnd = parseTime(form.get("endTime")); } catch (IllegalArgumentException ignored) { }
        for (ParkingSpace candidate : SERVICE.eligibleSpacesForReservation(vehicleForOptions, optionStart, optionEnd, selectedLot, selectedJunction)) {
            String candidateLot = SERVICE.lotForSpace(candidate.getSpaceId());
            Map<String, Integer> candidateTimes = SERVICE.travelTimesFrom(selectedJunction);
            int candidateMinutes = candidateTimes.getOrDefault(candidateLot, RoadNetwork.UNREACHABLE);
            h.append("<option value='").append(escape(candidate.getSpaceId())).append("' ").append(candidate.getSpaceId().equals(form.get("spaceId")) ? "selected" : "").append(">").append(escape(candidate.getSpaceId())).append(" · ").append(escape(candidateLot)).append(" · ").append(candidateMinutes).append(" min</option>");
        }
        h.append("</select></div><button ").append(SERVICE.vehicles.isEmpty() ? "disabled" : "").append(">Reserve selected lot / space</button></form>")
         .append("<hr style='border:0;border-top:1px solid #edf0f6;margin:20px 0'><h2>Travel time from your junction</h2>")
         .append("<form method='get' action='/reservations'><div class='field'><label>Starting junction</label><select name='junction'>");
        for (String junction : SERVICE.junctions()) {
            h.append("<option value='").append(escape(junction)).append("' ")
             .append(selectedJunction.equals(junction) ? "selected" : "").append(">")
             .append(escape(junction.replace('-', ' '))).append("</option>");
        }
        h.append("</select></div><button type='submit'>Calculate travel times</button></form>")
         .append("<div class='tablewrap' style='margin-top:12px'><table><thead><tr><th>Lot</th><th>Route time</th><th>Free spaces</th><th>Choice</th></tr></thead><tbody>");
        Map<String, Integer> routeTimes = SERVICE.travelTimesFrom(selectedJunction);
        for (ParkingLot lot : SERVICE.lots) {
            int minutes = routeTimes.getOrDefault(lot.getLotId(), RoadNetwork.UNREACHABLE);
            long free = lot.getSpaces().stream().filter(space -> !space.isOccupied()).count();
            h.append("<tr><td><b>").append(escape(lot.getLotId())).append("</b></td><td>")
             .append(minutes >= RoadNetwork.UNREACHABLE ? "Unreachable" : minutes + " min")
             .append("</td><td>").append(free).append("</td><td>");
            if (minutes < RoadNetwork.UNREACHABLE) {
                h.append("<form method='get' action='/reservations'><input type='hidden' name='junction' value='")
                 .append(escape(selectedJunction)).append("'><input type='hidden' name='lotId' value='")
                 .append(escape(lot.getLotId())).append("'><button type='submit'>Select lot</button></form>");
            } else h.append("—");
            h.append("</td></tr>");
        }
        h.append("</tbody></table></div></section>");
        }
        h.append(session.isAdmin() ? "<section class='panel'><h2>All reservation records</h2>" : "<section class='panel'><h2>My reservations</h2>");
        boolean hasVisibleReservations = session.isAdmin() ? !SERVICE.reservations.isEmpty() : !SERVICE.reservationsForUser(session.mobile).isEmpty();
        if (!hasVisibleReservations) h.append("<p>No reservations yet.</p>");
        else {
            h.append("<div class='tablewrap'><table><thead><tr><th>Reservation</th><th>Vehicle</th><th>Space / lot</th><th>Interval</th><th>Estimated cost</th><th>Status</th><th>Actions</th></tr></thead><tbody>");
            for (Reservation r : SERVICE.reservations) {
                if (!session.isAdmin() && !SERVICE.isOwnedVehicle(r.getVehicleId(), session.mobile)) continue;
                h.append("<tr><td>").append(escape(r.getReservationId())).append("</td><td>").append(escape(r.getVehicleId()))
                 .append("</td><td>").append(escape(r.getSpaceId())).append(" / ").append(escape(SERVICE.lotForSpace(r.getSpaceId())))
                 .append("</td><td>").append(escape(SERVICE.reservationDateText(r))).append(" ").append(SERVICE.timeText(r.getStartTime())).append("–").append(SERVICE.timeText(r.getEndTime()))
                 .append("</td><td>₹").append(SERVICE.estimatedCost(r.getVehicleId(), r.getStartTime(), r.getEndTime()))
                 .append("</td><td>").append(escape(r.getStatus())).append("</td><td>");
                if (r.isActive()) h.append("<form style='display:inline-block;margin-right:4px' method='post' action='/cancel'><input type='hidden' name='reservationId' value='").append(escape(r.getReservationId())).append("'><button>Cancel</button></form><form style='display:inline-block' method='post' action='/checkin'><input type='hidden' name='reservationId' value='").append(escape(r.getReservationId())).append("'><button>Check in</button></form>");
                else h.append("—");
                h.append("</td></tr>");
            }
            h.append("</tbody></table></div>");
        }
        h.append(session.isAdmin() ? "</section>" : "</section></div>");
    }

    private static void renderRevenueReports(StringBuilder h) {
        LocalDate today = LocalDate.now();
        int todayTotal = SERVICE.payments.stream().filter(p -> "PAID".equalsIgnoreCase(p.status) && today.toString().equals(p.date)).mapToInt(p -> p.amount).sum();
        int allTotal = SERVICE.payments.stream().filter(p -> "PAID".equalsIgnoreCase(p.status)).mapToInt(p -> p.amount).sum();
        h.append("<section class='cards'>");
        metric(h, "Today's revenue", "₹" + todayTotal, "Completed checkouts today");
        metric(h, "Total recorded revenue", "₹" + allTotal, "All saved receipts");
        metric(h, "Paid sessions", String.valueOf(SERVICE.payments.stream().filter(p -> "PAID".equalsIgnoreCase(p.status)).count()), "Completed demo / legacy payments");
        h.append("</section><section class='panel'><h2>Daily revenue</h2>");
        Map<String,Integer> daily = new TreeMap<>(Comparator.reverseOrder());
        for (PaymentRecord payment : SERVICE.payments) if ("PAID".equalsIgnoreCase(payment.status)) daily.merge(payment.date, payment.amount, Integer::sum);
        if (daily.isEmpty()) h.append("<p>No completed parking payments have been recorded yet.</p>");
        else { h.append("<div class='tablewrap'><table><thead><tr><th>Date</th><th>Completed sessions</th><th>Revenue</th></tr></thead><tbody>");
            for (Map.Entry<String,Integer> entry : daily.entrySet()) { long count=SERVICE.payments.stream().filter(p -> "PAID".equalsIgnoreCase(p.status) && p.date.equals(entry.getKey())).count(); h.append("<tr><td>").append(escape(entry.getKey())).append("</td><td>").append(count).append("</td><td><b>₹").append(entry.getValue()).append("</b></td></tr>"); }
            h.append("</tbody></table></div>"); }
        h.append("<p class='muted'>Revenue is calculated from completed checkouts, not from reservations that expired or were cancelled.</p></section><br><section class='panel'><h2>Recent receipts</h2>");
        if (SERVICE.payments.isEmpty()) h.append("<p>No receipts yet.</p>"); else { h.append("<div class='tablewrap'><table><thead><tr><th>Receipt</th><th>Date</th><th>Vehicle</th><th>Owner mobile</th><th>Duration</th><th>Amount</th><th>Receipt</th></tr></thead><tbody>");
            for (int i=SERVICE.payments.size()-1; i>=0; i--) { PaymentRecord p=SERVICE.payments.get(i); h.append("<tr><td>").append(escape(p.id)).append("</td><td>").append(escape(p.date)).append("</td><td>").append(escape(SERVICE.primaryIdFor(p.vehicleId))).append(" · ").append(escape(p.vehicleId)).append("</td><td>").append(escape(p.ownerMobile)).append("</td><td>").append(p.durationMinutes).append(" min</td><td>₹").append(p.amount).append("</td><td><a class='btn' href='/receipt?id=").append(escape(p.id)).append("'>View / print</a></td></tr>"); }
            h.append("</tbody></table></div>"); }
        h.append("</section>");
    }

    private static void renderUserHistory(StringBuilder h, UserSession session) {
        String targetMobile = session.mobile;
        if (session.isAdmin()) {
            String query = "";
            // The GET query is supplied in the form map; admin can search by mobile or name.
            // If no query is supplied, show a searchable form only.
            h.append("<section class='panel'><h2>Find a user's parking history</h2><form method='get' action='/my-history'><div class='field'><label>User name, mobile number, vehicle ID, or registration number</label><input name='user' value='").append(escape(ADMIN_HISTORY_QUERY == null ? "" : ADMIN_HISTORY_QUERY)).append("' placeholder='VH000001, TN01AB1234, mobile, or name'></div><button>Search history</button></form></section><br>");
            // targetMobile is populated below by the request handler via a small query-state field.
            targetMobile = ADMIN_HISTORY_QUERY;
            if (targetMobile == null || targetMobile.isBlank()) { h.append("<section class='panel'><p>Enter a registered user's mobile number or name to view their reservations, visits, and receipts.</p></section>"); return; }
            String q = targetMobile.trim().toLowerCase(Locale.ROOT);
            UserAccount found = SERVICE.users.values().stream()
                    .filter(u -> u.mobile.equalsIgnoreCase(q) || u.name.toLowerCase(Locale.ROOT).contains(q))
                    .findFirst().orElse(null);
            // Admin can also search by a vehicle's unique ID (for example VH000001)
            // or by the vehicle registration number. Show the history of that vehicle's owner.
            if (found == null) {
                String ownerMobile = SERVICE.vehicles.values().stream()
                        .filter(v -> SERVICE.primaryIdFor(v.getVehicleId()).toLowerCase(Locale.ROOT).contains(q)
                                || v.getVehicleId().toLowerCase(Locale.ROOT).contains(q))
                        .map(v -> SERVICE.vehicleOwners.getOrDefault(v.getVehicleId(), ""))
                        .filter(owner -> !owner.isBlank())
                        .findFirst().orElse("");
                if (!ownerMobile.isBlank()) found = SERVICE.users.get(ownerMobile);
            }
            if (found == null) { h.append("<section class='panel'><p>No user or vehicle matched that search. Check the vehicle ID, registration number, mobile number, or name.</p></section>"); return; }
            targetMobile = found.mobile;
            h.append("<section class='panel'><h2>History for ").append(escape(found.name)).append(" · ").append(escape(found.mobile)).append("</h2>");
        } else {
            h.append("<section class='panel'><h2>My parking history</h2><p class='muted'>Past reservations and completed parking payments for your account.</p>");
        }
        final String ownerMobile = targetMobile;
        List<PaymentRecord> own = SERVICE.payments.stream().filter(p -> Objects.equals(p.ownerMobile, ownerMobile)).toList();
        h.append("<h3>Parking payments and receipts</h3>");
        if (own.isEmpty()) h.append("<p>No completed parking sessions yet.</p>");
        else { h.append("<div class='tablewrap'><table><thead><tr><th>Receipt</th><th>Date</th><th>Vehicle</th><th>Space</th><th>Duration</th><th>Amount</th><th>Action</th></tr></thead><tbody>");
            for (int i=own.size()-1;i>=0;i--) { PaymentRecord p=own.get(i); h.append("<tr><td>").append(escape(p.id)).append("</td><td>").append(escape(p.date)).append("</td><td>").append(escape(p.vehicleId)).append("</td><td>").append(escape(p.spaceId)).append("</td><td>").append(p.durationMinutes).append(" minutes</td><td>₹").append(p.amount).append("</td><td><a class='btn' href='/receipt?id=").append(escape(p.id)).append("'>View / print</a></td></tr>"); }
            h.append("</tbody></table></div>"); }
        h.append("<h3 style='margin-top:24px'>Reservation history</h3>");
        List<Reservation> ownReservations=SERVICE.reservationsForUser(ownerMobile);
        if(ownReservations.isEmpty()) h.append("<p>No reservations recorded yet.</p>"); else { h.append("<div class='tablewrap'><table><thead><tr><th>Reservation</th><th>Vehicle</th><th>Space</th><th>Time</th><th>Status</th><th>Estimated cost</th></tr></thead><tbody>"); for(Reservation r:ownReservations) h.append("<tr><td>").append(escape(r.getReservationId())).append("</td><td>").append(escape(r.getVehicleId())).append("</td><td>").append(escape(r.getSpaceId())).append("</td><td>").append(escape(SERVICE.reservationDateText(r))).append(" ").append(SERVICE.timeText(r.getStartTime())).append("–").append(SERVICE.timeText(r.getEndTime())).append("</td><td>").append(escape(r.getStatus())).append("</td><td>₹").append(SERVICE.estimatedCost(r.getVehicleId(),r.getStartTime(),r.getEndTime())).append("</td></tr>"); h.append("</tbody></table></div>"); }
        h.append("</section>");
    }

    private static void renderPayments(StringBuilder h, UserSession session) {
        if (session.isAdmin()) { h.append("<section class='panel'><h2>Demo payment portal</h2><p>Use the Revenue reports page to review recorded payments. The demo checkout is available to user accounts.</p><a class='btn' href='/reports'>Revenue reports</a></section>"); return; }
        List<PaymentRecord> own = SERVICE.payments.stream().filter(p -> Objects.equals(p.ownerMobile, session.mobile)).toList();
        int pending = (int) own.stream().filter(p -> !"PAID".equalsIgnoreCase(p.status)).count();
        h.append("<section class='cards'>");
        metric(h, "Receipts", String.valueOf(own.size()), "Your completed parking sessions");
        metric(h, "Awaiting demo payment", String.valueOf(pending), "Choose a method below");
        h.append("</section><section class='panel'><h2>Demo payment portal</h2><p class='muted'>Choose a method and review the amount before confirming the second step. This is a simulation only; no real transaction is made.</p>");
        if (own.isEmpty()) h.append("<p>No parking charges yet. Park a vehicle and check out to create a charge.</p>");
        else { h.append("<div class='tablewrap'><table><thead><tr><th>Receipt</th><th>Vehicle</th><th>Duration</th><th>Amount</th><th>Status</th><th>Payment method / action</th></tr></thead><tbody>");
            for (int i=own.size()-1;i>=0;i--) { PaymentRecord p=own.get(i); h.append("<tr><td>").append(escape(p.id)).append("</td><td>").append(escape(SERVICE.primaryIdFor(p.vehicleId))).append(" · ").append(escape(p.vehicleId)).append("</td><td>").append(p.durationMinutes).append(" min</td><td><b>₹").append(p.amount).append("</b></td><td><b>").append(escape(p.status)).append("</b></td><td>");
                if (!"PAID".equalsIgnoreCase(p.status)) { h.append("<form method='post' action='/demo-pay' style='display:flex;gap:8px;align-items:center;flex-wrap:wrap'><input type='hidden' name='receiptId' value='").append(escape(p.id)).append("'><input type='hidden' name='step' value='review'><select name='paymentMethod' required><option value='DEMO_UPI'>Demo UPI</option><option value='DEMO_CARD'>Demo card</option><option value='DEMO_CASH'>Cash (demo)</option></select><button>Review payment</button></form>"); }
                else h.append(escape(p.method.isBlank() ? "Recorded / legacy payment" : p.method.replace("DEMO_", "Demo "))).append(" · <a href='/receipt?id=").append(escape(p.id)).append("'>Receipt</a>");
                h.append("</td></tr>"); }
            h.append("</tbody></table></div>"); }
        h.append("</section>");
    }

    private static void renderDemoCheckout(StringBuilder h, Map<String,String> form, UserSession session) {
        if (session.isAdmin()) { h.append("<section class='panel'><h2>Demo checkout unavailable</h2></section>"); return; }
        String id = form.getOrDefault("receiptId", ""); String method = form.getOrDefault("paymentMethod", "");
        PaymentRecord p = SERVICE.payments.stream().filter(x -> x.id.equalsIgnoreCase(id) && Objects.equals(x.ownerMobile, session.mobile)).findFirst().orElse(null);
        if (p == null || !Set.of("DEMO_UPI", "DEMO_CARD", "DEMO_CASH").contains(method) || "PAID".equalsIgnoreCase(p.status)) { h.append("<section class='panel'><h2>Cannot continue payment</h2><p>Receipt unavailable, already paid, or invalid method.</p><a class='btn' href='/payments'>Return to payments</a></section>"); return; }
        h.append("<section class='panel' style='max-width:760px;margin:auto'><h2>Review demo payment</h2><p>Receipt: <b>").append(escape(p.id)).append("</b></p><p>Vehicle: <b>").append(escape(p.vehicleId)).append("</b></p><p>Parking duration: <b>").append(p.durationMinutes).append(" minutes</b></p><h2>Amount due: ₹").append(p.amount).append("</h2><p>Selected method: <b>").append(escape(method.replace("DEMO_", "Demo "))).append("</b></p><div style='background:#fff6df;padding:14px;border-radius:10px;margin:16px 0'><b>Demo mode only</b><p>No bank or payment gateway is connected. Do not enter real UPI IDs, card numbers, CVVs, or banking passwords. Status changes only after you confirm this simulation.</p>");
        if ("DEMO_UPI".equals(method)) h.append("<p>Demo UPI reference: DEMO-").append(escape(p.id)).append("</p>"); else if ("DEMO_CARD".equals(method)) h.append("<p>Test card simulation: no card details required.</p>"); else h.append("<p>Cash selected: this records a simulated counter-payment only.</p>");
        h.append("</div><form method='post' action='/demo-pay'><input type='hidden' name='receiptId' value='").append(escape(p.id)).append("'><input type='hidden' name='paymentMethod' value='").append(escape(method)).append("'><input type='hidden' name='step' value='complete'><label style='display:flex;gap:10px;align-items:flex-start;margin:14px 0'><input style='width:auto;margin-top:4px' type='checkbox' name='confirmDemo' value='yes' required><span>I understand this is simulated and no real money will be transferred.</span></label><button>Complete simulated payment</button> <a class='btn' href='/payments'>Cancel</a></form></section>");
    }

    private static void renderReceipt(StringBuilder h, Map<String,String> form, UserSession session) {
        String id=form.getOrDefault("id", "");
        PaymentRecord payment=SERVICE.payments.stream().filter(p -> p.id.equalsIgnoreCase(id)).findFirst().orElse(null);
        if(payment==null || (!session.isAdmin() && !Objects.equals(payment.ownerMobile,session.mobile))) { h.append("<section class='panel'><h2>Receipt not found</h2><p>This receipt does not exist or does not belong to your account.</p></section>"); return; }
        h.append("<section class='panel' style='max-width:760px;margin:auto'><div style='display:flex;justify-content:space-between;gap:12px;align-items:center;flex-wrap:wrap'><h2>SmartPark Parking Receipt</h2><button onclick='window.print()'>Print / Save as PDF</button></div><hr><p><b>Receipt number:</b> ").append(escape(payment.id)).append("</p><p><b>Date:</b> ").append(escape(payment.date)).append("</p><p><b>Vehicle primary ID:</b> ").append(escape(SERVICE.primaryIdFor(payment.vehicleId))).append("</p><p><b>Registration number:</b> ").append(escape(payment.vehicleId)).append("</p><p><b>Parking space:</b> ").append(escape(payment.spaceId)).append("</p><p><b>Parking start:</b> ").append(SERVICE.timeText(payment.startTime)).append("</p><p><b>Checkout:</b> ").append(SERVICE.timeText(payment.endTime)).append("</p><p><b>Duration:</b> ").append(payment.durationMinutes).append(" minutes</p><p><b>Hourly rate:</b> ₹").append(payment.rate).append("</p><p><b>Payment status:</b> ").append(escape(payment.status)).append("</p><p><b>Payment method:</b> ").append(escape(payment.method.isBlank() ? "Not selected" : payment.method)).append("</p><hr><h2>Parking charge: ₹").append(payment.amount).append("</h2><p class='muted'>Demo payment receipt from SmartPark. No real money is transferred in demo mode.</p><a class='btn' href='").append(session.isAdmin()?"/reports":"/my-history").append("'>Back</a></section>");
    }

    private static void renderWaiting(StringBuilder h) {
        h.append("<section class='panel'><h2>Waiting queue</h2>");
        if (SERVICE.waitingQueue.isEmpty()) h.append("<p>No waiting requests. If all compatible spaces are full, the next version can add a request form and automatic reconsideration.</p>");
        else {
            List<ParkingRequest> sorted = new ArrayList<>(SERVICE.waitingQueue);
            sorted.sort(Comparator.comparingInt(ParkingRequest::getAccessPriority).thenComparingInt(ParkingRequest::getRequestTime).thenComparing(ParkingRequest::getRequestId, String.CASE_INSENSITIVE_ORDER));
            h.append("<table><thead><tr><th>Request</th><th>Vehicle</th><th>Priority</th><th>Arrival</th><th>Status</th></tr></thead><tbody>");
            for (ParkingRequest r : sorted) h.append("<tr><td>").append(escape(r.getRequestId())).append("</td><td>").append(escape(r.getVehicle().getVehicleId())).append("</td><td>").append(r.getAccessPriority()).append("</td><td>").append(SERVICE.timeText(r.getRequestTime())).append("</td><td>").append(escape(r.getStatus())).append("</td></tr>");
            h.append("</tbody></table>");
        }
        h.append("</section>");
    }

    private static void renderEvents(StringBuilder h) {
        h.append("<section class='panel'><h2>Event history</h2><p><b>Total events: ").append(SERVICE.history.getEventCount()).append("</b></p>");
        List<String> events = SERVICE.history.getEvents();
        if (events.isEmpty()) h.append("<p>No events recorded yet.</p>");
        else { h.append("<div class='tablewrap'><table><thead><tr><th>#</th><th>Event</th></tr></thead><tbody>");
            for (int i = events.size() - 1; i >= 0; i--) h.append("<tr><td>").append(i + 1).append("</td><td>").append(escape(events.get(i))).append("</td></tr>");
            h.append("</tbody></table></div>"); }
        h.append("</section>");
    }

    private static Map<String, String> parseForm(String raw) {
        Map<String, String> result = new HashMap<>();
        if (raw == null || raw.isBlank()) return result;
        for (String pair : raw.split("&")) {
            String[] bits = pair.split("=", 2);
            String key = URLDecoder.decode(bits[0], StandardCharsets.UTF_8);
            String value = bits.length > 1 ? URLDecoder.decode(bits[1], StandardCharsets.UTF_8) : "";
            result.put(key, value);
        }
        return result;
    }

    private static String required(Map<String, String> form, String key) {
        String value = form.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Please provide " + key + ".");
        return value;
    }

    private static int parseTime(String text) {
        try {
            String[] parts = text.trim().split(":");
            if (parts.length != 2) throw new NumberFormatException();
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) throw new NumberFormatException();
            return hour * 60 + minute;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Time must be in 24-hour HH:MM format, for example 09:30.");
        }
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static final class UserAccount {
        final String name, mobile, passwordHash;
        UserAccount(String name, String mobile, String passwordHash) { this.name = name; this.mobile = mobile; this.passwordHash = passwordHash; }
    }

    private static final class AppNotification {
        final String mobile, text, pendingId;
        AppNotification(String mobile, String text, String pendingId) { this.mobile = mobile; this.text = text; this.pendingId = pendingId; }
    }
    private static final class PendingExtension {
        final String id, requesterMobile, requesterVehicle, spaceId, lotId, junction, currentReservationId;
        final int start, end, travelMinutes;
        PendingExtension(String id, String requesterMobile, String requesterVehicle, String spaceId, String lotId, String junction, String currentReservationId, int start, int end, int travelMinutes) {
            this.id=id; this.requesterMobile=requesterMobile; this.requesterVehicle=requesterVehicle; this.spaceId=spaceId; this.lotId=lotId; this.junction=junction; this.currentReservationId=currentReservationId; this.start=start; this.end=end; this.travelMinutes=travelMinutes;
        }
    }

    private static final class PaymentRecord {
        final String id, vehicleId, ownerMobile, spaceId, date;
        final int startTime, endTime, durationMinutes, rate, amount;
        String status, method;
        PaymentRecord(String id, String vehicleId, String ownerMobile, String spaceId, String date, int startTime, int endTime, int durationMinutes, int rate, int amount) {
            this(id, vehicleId, ownerMobile, spaceId, date, startTime, endTime, durationMinutes, rate, amount, "PENDING", "");
        }
        PaymentRecord(String id, String vehicleId, String ownerMobile, String spaceId, String date, int startTime, int endTime, int durationMinutes, int rate, int amount, String status, String method) {
            this.id=id; this.vehicleId=vehicleId; this.ownerMobile=ownerMobile; this.spaceId=spaceId; this.date=date; this.startTime=startTime; this.endTime=endTime; this.durationMinutes=durationMinutes; this.rate=rate; this.amount=amount; this.status=status == null ? "PENDING" : status; this.method=method == null ? "" : method;
        }
    }

    private static final class ParkingService {
        final Map<String, Vehicle> vehicles = new LinkedHashMap<>();
        final Map<String, UserAccount> users = new LinkedHashMap<>();
        final Map<String, String> vehicleOwners = new LinkedHashMap<>();
        final Map<String, String> vehiclePrimaryIds = new LinkedHashMap<>();
        final Map<String, Integer> vehicleVisitCounts = new LinkedHashMap<>();
        int vehicleIdCounter = 1;
        final List<AppNotification> notifications = new ArrayList<>();
        final Map<String, PendingExtension> pendingExtensions = new LinkedHashMap<>();
        final Set<String> reminderSent = new HashSet<>();
        int pendingCounter = 1;
        final List<ParkingLot> lots = new ArrayList<>();
        final List<Reservation> reservations = new ArrayList<>();
        final Map<String, LocalDate> reservationDates = new LinkedHashMap<>();
        final Map<String, ParkingSpace> parkedVehicles = new LinkedHashMap<>();
        final Map<String, Integer> parkingStartTimes = new LinkedHashMap<>();
        final List<PaymentRecord> payments = new ArrayList<>();
        int receiptCounter = 1;
        final PriorityQueue<ParkingRequest> waitingQueue = new PriorityQueue<>(Comparator
                .comparingInt(ParkingRequest::getAccessPriority)
                .thenComparingInt(ParkingRequest::getRequestTime)
                .thenComparing(ParkingRequest::getRequestId, String.CASE_INSENSITIVE_ORDER));
        final EventHistory history = new EventHistory();
        final RoadNetwork roads = new RoadNetwork();
        final SlotSelectionPolicy policy = new DefaultSlotSelectionPolicy();
        int currentTime = LocalTime.now().getHour() * 60 + LocalTime.now().getMinute();
        LocalDate currentDate = LocalDate.now();
        int reservationCounter = 1;

        ParkingService() {
            ParkingLot a = new ParkingLot("LOT-A"), b = new ParkingLot("LOT-B"), c = new ParkingLot("LOT-C");
            addStandardSpaces(a, "A"); addStandardSpaces(b, "B"); addStandardSpaces(c, "C");
            lots.add(a); lots.add(b); lots.add(c);
            roads.addUndirectedEdge("ENTRY", "WEST-GATE", 3);
            roads.addUndirectedEdge("ENTRY", "JUNCTION-1", 4);
            roads.addUndirectedEdge("WEST-GATE", "LOT-A", 5);
            roads.addUndirectedEdge("WEST-GATE", "JUNCTION-2", 4);
            roads.addUndirectedEdge("JUNCTION-1", "JUNCTION-2", 2);
            roads.addUndirectedEdge("JUNCTION-1", "LOT-B", 6);
            roads.addUndirectedEdge("JUNCTION-2", "LOT-C", 3);
            roads.addUndirectedEdge("LOT-A", "LOT-B", 4);
            roads.addUndirectedEdge("LOT-B", "LOT-C", 4);
            roads.addUndirectedEdge("JUNCTION-1", "EAST-GATE", 5);
            roads.addUndirectedEdge("EAST-GATE", "LOT-C", 2);
            loadDatabase();
        }

        private Connection databaseConnection() throws SQLException {
            return DriverManager.getConnection("jdbc:sqlite:smartparking.db");
        }

        private void loadDatabase() {
            try {
                Class.forName("org.sqlite.JDBC");
                try (Connection c = databaseConnection(); Statement st = c.createStatement()) {
                    st.executeUpdate("CREATE TABLE IF NOT EXISTS vehicles (vehicle_id TEXT PRIMARY KEY, vehicle_type TEXT NOT NULL, permit_required INTEGER NOT NULL, permit_valid INTEGER NOT NULL)");
                    st.executeUpdate("CREATE TABLE IF NOT EXISTS vehicle_registry (vehicle_uid TEXT PRIMARY KEY, vehicle_pk INTEGER UNIQUE, registration_no TEXT UNIQUE NOT NULL)");
                    st.executeUpdate("CREATE TABLE IF NOT EXISTS vehicle_visits (vehicle_id TEXT PRIMARY KEY, visit_count INTEGER NOT NULL DEFAULT 0)");
                    st.executeUpdate("CREATE TABLE IF NOT EXISTS users (mobile TEXT PRIMARY KEY, name TEXT NOT NULL, password_hash TEXT NOT NULL DEFAULT '')");
                    st.executeUpdate("CREATE TABLE IF NOT EXISTS vehicle_owners (vehicle_id TEXT PRIMARY KEY, mobile TEXT NOT NULL)");
                    try { st.executeUpdate("ALTER TABLE users ADD COLUMN password_hash TEXT NOT NULL DEFAULT ''"); } catch (SQLException alreadyExists) { /* column already exists */ }
                    st.executeUpdate("CREATE TABLE IF NOT EXISTS reservations (reservation_id TEXT PRIMARY KEY, vehicle_id TEXT NOT NULL, space_id TEXT NOT NULL, start_time INTEGER NOT NULL, end_time INTEGER NOT NULL, status TEXT NOT NULL, reservation_date TEXT NOT NULL DEFAULT '')");
                    try { st.executeUpdate("ALTER TABLE reservations ADD COLUMN reservation_date TEXT NOT NULL DEFAULT ''"); } catch (SQLException alreadyExists) { /* column already exists */ }
                    st.executeUpdate("UPDATE reservations SET reservation_date=date('now','localtime') WHERE reservation_date IS NULL OR reservation_date=''");
                    // Repair legacy records incorrectly expired solely because the stored clock time was stale.
                    st.executeUpdate("UPDATE reservations SET status='ACTIVE' WHERE status='EXPIRED' AND reservation_date=date('now','localtime') AND end_time > (CAST(strftime('%H','now','localtime') AS INTEGER)*60 + CAST(strftime('%M','now','localtime') AS INTEGER))");
                    st.executeUpdate("CREATE TABLE IF NOT EXISTS parked_vehicles (vehicle_id TEXT PRIMARY KEY, space_id TEXT NOT NULL)");
                    st.executeUpdate("CREATE TABLE IF NOT EXISTS parking_start_times (vehicle_id TEXT PRIMARY KEY, start_time INTEGER NOT NULL, start_date TEXT NOT NULL DEFAULT '')");
                    try { st.executeUpdate("ALTER TABLE parking_start_times ADD COLUMN start_date TEXT NOT NULL DEFAULT ''"); } catch (SQLException alreadyExists) { /* column exists */ }
                    st.executeUpdate("CREATE TABLE IF NOT EXISTS app_notifications (notification_id INTEGER PRIMARY KEY AUTOINCREMENT, mobile TEXT NOT NULL, notification_text TEXT NOT NULL, pending_id TEXT)");
                    st.executeUpdate("CREATE TABLE IF NOT EXISTS app_state (state_key TEXT PRIMARY KEY, state_value TEXT NOT NULL)");
                    st.executeUpdate("CREATE TABLE IF NOT EXISTS event_log (event_id INTEGER PRIMARY KEY AUTOINCREMENT, event_text TEXT NOT NULL)");
                    st.executeUpdate("CREATE TABLE IF NOT EXISTS parking_payments (receipt_id TEXT PRIMARY KEY, vehicle_id TEXT NOT NULL, owner_mobile TEXT, space_id TEXT NOT NULL, payment_date TEXT NOT NULL, start_time INTEGER NOT NULL, end_time INTEGER NOT NULL, duration_minutes INTEGER NOT NULL, rate INTEGER NOT NULL, amount INTEGER NOT NULL, payment_status TEXT NOT NULL DEFAULT 'PENDING', payment_method TEXT NOT NULL DEFAULT '')");
                    try { st.executeUpdate("ALTER TABLE parking_payments ADD COLUMN payment_status TEXT NOT NULL DEFAULT 'PENDING'"); } catch (SQLException alreadyExists) { /* column exists */ }
                    try { st.executeUpdate("ALTER TABLE parking_payments ADD COLUMN payment_method TEXT NOT NULL DEFAULT ''"); } catch (SQLException alreadyExists) { /* column exists */ }
                    st.executeUpdate("UPDATE parking_payments SET payment_status='PENDING', payment_method='' WHERE payment_method='LEGACY'");
                }
                try (Connection c = databaseConnection()) {
                    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT name, mobile, password_hash FROM users")) {
                        while (rs.next()) users.put(rs.getString(2), new UserAccount(rs.getString(1), rs.getString(2), rs.getString(3)));
                    }
                    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT vehicle_id, mobile FROM vehicle_owners")) {
                        while (rs.next()) vehicleOwners.put(rs.getString(1), rs.getString(2));
                    }
                    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT vehicle_id, vehicle_type, permit_required, permit_valid FROM vehicles")) {
                        while (rs.next()) {
                            String id = rs.getString(1);
                            vehicles.put(id, new Vehicle(id, rs.getString(2), rs.getInt(3) != 0, rs.getInt(4) != 0));
                        }
                    }
                    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT registration_no, vehicle_uid FROM vehicle_registry ORDER BY registration_no")) {
                        Set<String> usedIds = new HashSet<>();
                        while (rs.next()) {
                            String registration = rs.getString(1), uid = rs.getString(2);
                            if (vehicles.containsKey(registration) && uid != null && uid.matches("VH[0-9]{6}") && usedIds.add(uid)) vehiclePrimaryIds.put(registration, uid);
                        }
                    }
                    for (String registration : vehicles.keySet()) ensurePrimaryId(c, registration);
                    vehicleIdCounter = vehiclePrimaryIds.values().stream().filter(v -> v.matches("VH[0-9]{6}"))
                        .mapToInt(v -> Integer.parseInt(v.substring(2)) + 1).max().orElse(1);
                    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT vehicle_id, visit_count FROM vehicle_visits")) { while (rs.next()) vehicleVisitCounts.put(rs.getString(1), rs.getInt(2)); }
                    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT reservation_id, vehicle_id, space_id, start_time, end_time, status, reservation_date FROM reservations ORDER BY reservation_id")) {
                        while (rs.next()) {
                            Reservation r = new Reservation(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5));
                            r.restoreStatus(rs.getString(6));
                            reservations.add(r);
                            try { reservationDates.put(r.getReservationId(), LocalDate.parse(rs.getString(7))); } catch (RuntimeException badDate) { reservationDates.put(r.getReservationId(), LocalDate.now()); }
                            try { reservationCounter = Math.max(reservationCounter, Integer.parseInt(r.getReservationId().substring(1)) + 1); } catch (RuntimeException ignored) { }
                        }
                    }
                    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT vehicle_id, space_id FROM parked_vehicles")) {
                        while (rs.next()) {
                            ParkingSpace space = findSpace(rs.getString(2));
                            if (space != null) { space.occupy(); parkedVehicles.put(rs.getString(1), space); }
                        }
                    }
                    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT vehicle_id,start_time FROM parking_start_times")) {
                        while (rs.next()) parkingStartTimes.put(rs.getString(1), rs.getInt(2));
                    }
                    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT mobile,notification_text,pending_id FROM app_notifications ORDER BY notification_id")) {
                        while (rs.next()) notifications.add(new AppNotification(rs.getString(1), rs.getString(2), rs.getString(3)));
                    }
                    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT state_key, state_value FROM app_state")) {
                        while (rs.next()) {
                            if (rs.getString(1).equals("currentDate")) currentDate = LocalDate.parse(rs.getString(2));
                            if (rs.getString(1).equals("reservationCounter")) reservationCounter = Math.max(reservationCounter, Integer.parseInt(rs.getString(2)));
                        }
                    }
                    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT receipt_id,vehicle_id,owner_mobile,space_id,payment_date,start_time,end_time,duration_minutes,rate,amount,payment_status,payment_method FROM parking_payments ORDER BY payment_date,receipt_id")) {
                        while (rs.next()) {
                            String storedMethod = rs.getString(12); String storedStatus = rs.getString(11);
                            if (storedMethod == null || storedMethod.isBlank() || "LEGACY".equalsIgnoreCase(storedMethod)) { storedMethod = ""; storedStatus = "PENDING"; }
                            PaymentRecord payment = new PaymentRecord(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getInt(6),rs.getInt(7),rs.getInt(8),rs.getInt(9),rs.getInt(10),storedStatus,storedMethod);
                            payments.add(payment);
                            try { receiptCounter = Math.max(receiptCounter, Integer.parseInt(payment.id.substring(1)) + 1); } catch (RuntimeException ignored) { }
                        }
                    }
                }
                System.out.println("SQLite database connected: smartparking.db");
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("SQLite JDBC driver missing. Run the application with Maven as described in README.md.", e);
            } catch (SQLException e) {
                throw new IllegalStateException("Could not initialize/load smartparking.db: " + e.getMessage(), e);
            }
        }

        synchronized void saveDatabase() {
            // Repair legacy duplicate primary IDs before writing to SQLite.
            Set<String> usedPrimaryIds = new HashSet<>();
            for (String registration : new ArrayList<>(vehiclePrimaryIds.keySet())) {
                String uid = vehiclePrimaryIds.get(registration);
                if (uid == null || !uid.matches("VH[0-9]{6}") || !usedPrimaryIds.add(uid)) {
                    String replacementId;
                    do { replacementId = String.format(Locale.ROOT, "VH%06d", vehicleIdCounter++); }
                    while (!usedPrimaryIds.add(replacementId));
                    vehiclePrimaryIds.put(registration, replacementId);
                }
            }
            try (Connection c = databaseConnection()) {
                c.setAutoCommit(false);
                try (Statement st = c.createStatement()) {
                    st.executeUpdate("DELETE FROM vehicles");
                    st.executeUpdate("DELETE FROM users");
                    st.executeUpdate("DELETE FROM vehicle_owners");
                    st.executeUpdate("DELETE FROM reservations");
                    st.executeUpdate("DELETE FROM parked_vehicles");
                    st.executeUpdate("DELETE FROM app_state");
                    st.executeUpdate("DELETE FROM event_log");
                    st.executeUpdate("DELETE FROM vehicle_registry");
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO vehicles(vehicle_id,vehicle_type,permit_required,permit_valid) VALUES(?,?,?,?)")) {
                    for (Vehicle v : vehicles.values()) { ps.setString(1, v.getVehicleId()); ps.setString(2, v.getVehicleType()); ps.setInt(3, v.isPermitRequired()?1:0); ps.setInt(4, v.isPermitValid()?1:0); ps.addBatch(); }
                    ps.executeBatch();
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO users(mobile,name,password_hash) VALUES(?,?,?)")) {
                    for (UserAccount account : users.values()) { ps.setString(1, account.mobile); ps.setString(2, account.name); ps.setString(3, account.passwordHash); ps.addBatch(); }
                    ps.executeBatch();
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO vehicle_owners(vehicle_id,mobile) VALUES(?,?)")) {
                    for (Map.Entry<String, String> e : vehicleOwners.entrySet()) { ps.setString(1, e.getKey()); ps.setString(2, e.getValue()); ps.addBatch(); }
                    ps.executeBatch();
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO reservations(reservation_id,vehicle_id,space_id,start_time,end_time,status,reservation_date) VALUES(?,?,?,?,?,?,?)")) {
                    for (Reservation r : reservations) { ps.setString(1,r.getReservationId()); ps.setString(2,r.getVehicleId()); ps.setString(3,r.getSpaceId()); ps.setInt(4,r.getStartTime()); ps.setInt(5,r.getEndTime()); ps.setString(6,r.getStatus()); ps.setString(7,reservationDates.getOrDefault(r.getReservationId(), currentDate).toString()); ps.addBatch(); }
                    ps.executeBatch();
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO parked_vehicles(vehicle_id,space_id) VALUES(?,?)")) {
                    for (Map.Entry<String, ParkingSpace> e : parkedVehicles.entrySet()) { ps.setString(1,e.getKey()); ps.setString(2,e.getValue().getSpaceId()); ps.addBatch(); }
                    ps.executeBatch();
                }
                try (Statement st = c.createStatement()) { st.executeUpdate("DELETE FROM parking_start_times"); }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO parking_start_times(vehicle_id,start_time,start_date) VALUES(?,?,?)")) {
                    for (Map.Entry<String,Integer> e : parkingStartTimes.entrySet()) { ps.setString(1,e.getKey()); ps.setInt(2,e.getValue()); ps.setString(3,currentDate.toString()); ps.addBatch(); }
                    ps.executeBatch();
                }
                try (Statement st = c.createStatement()) { st.executeUpdate("DELETE FROM app_notifications"); }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO app_notifications(mobile,notification_text,pending_id) VALUES(?,?,?)")) {
                    for (AppNotification n : notifications) { ps.setString(1,n.mobile); ps.setString(2,n.text); ps.setString(3,n.pendingId); ps.addBatch(); }
                    ps.executeBatch();
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO vehicle_registry(vehicle_uid,registration_no) VALUES(?,?) ON CONFLICT(registration_no) DO NOTHING")) { for (Map.Entry<String,String> e : vehiclePrimaryIds.entrySet()) { ps.setString(1,e.getValue()); ps.setString(2,e.getKey()); ps.addBatch(); } ps.executeBatch(); }
                try (Statement st = c.createStatement()) { st.executeUpdate("DELETE FROM vehicle_visits"); }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO vehicle_visits(vehicle_id,visit_count) VALUES(?,?)")) { for (Map.Entry<String,Integer> e : vehicleVisitCounts.entrySet()) { ps.setString(1,e.getKey()); ps.setInt(2,e.getValue()); ps.addBatch(); } ps.executeBatch(); }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO app_state(state_key,state_value) VALUES(?,?)")) {
                    ps.setString(1,"currentDate"); ps.setString(2,currentDate.toString()); ps.addBatch();
                    ps.setString(1,"reservationCounter"); ps.setString(2,String.valueOf(reservationCounter)); ps.addBatch(); ps.executeBatch();
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO event_log(event_text) VALUES(?)")) {
                    for (String event : history.getEvents()) { ps.setString(1,event); ps.addBatch(); }
                    ps.executeBatch();
                }
                try (Statement st = c.createStatement()) { st.executeUpdate("DELETE FROM parking_payments"); }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO parking_payments(receipt_id,vehicle_id,owner_mobile,space_id,payment_date,start_time,end_time,duration_minutes,rate,amount,payment_status,payment_method) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    for (PaymentRecord payment : payments) {
                        ps.setString(1,payment.id); ps.setString(2,payment.vehicleId); ps.setString(3,payment.ownerMobile); ps.setString(4,payment.spaceId); ps.setString(5,payment.date);
                        ps.setInt(6,payment.startTime); ps.setInt(7,payment.endTime); ps.setInt(8,payment.durationMinutes); ps.setInt(9,payment.rate); ps.setInt(10,payment.amount); ps.setString(11,payment.status); ps.setString(12,payment.method); ps.addBatch();
                    }
                    ps.executeBatch();
                }
                c.commit();
            } catch (SQLException e) {
                throw new IllegalStateException("Database save failed: " + e.getMessage(), e);
            }
        }

        private void addStandardSpaces(ParkingLot lot, String prefix) {
            lot.addSpace(new CarSpace(prefix + "01")); lot.addSpace(new CarSpace(prefix + "02"));
            lot.addSpace(new CarSpace(prefix + "03")); lot.addSpace(new TwoWheelerSpace(prefix + "04"));
            lot.addSpace(new TwoWheelerSpace(prefix + "05")); lot.addSpace(new TwoWheelerSpace(prefix + "06"));
            lot.addSpace(new AccessibleSpace(prefix + "07")); lot.addSpace(new CarSpace(prefix + "08"));
        }

        private void ensurePrimaryId(Connection c, String registration) throws SQLException {
            String existing = vehiclePrimaryIds.get(registration);
            if (existing != null && Collections.frequency(new ArrayList<>(vehiclePrimaryIds.values()), existing) == 1) return;
            vehiclePrimaryIds.remove(registration);
            String uid;
            do { uid = String.format(Locale.ROOT, "VH%06d", vehicleIdCounter++); } while (vehiclePrimaryIds.containsValue(uid));
            try (PreparedStatement ps=c.prepareStatement("INSERT OR IGNORE INTO vehicle_registry(vehicle_uid,registration_no) VALUES(?,?)")) { ps.setString(1,uid); ps.setString(2,registration); ps.executeUpdate(); }
            try (PreparedStatement ps=c.prepareStatement("SELECT vehicle_uid FROM vehicle_registry WHERE registration_no=?")) { ps.setString(1,registration); try(ResultSet rs=ps.executeQuery()) { if(rs.next()) vehiclePrimaryIds.put(registration,rs.getString(1)); else vehiclePrimaryIds.put(registration,uid); } }
        }
        synchronized String primaryIdFor(String registration) {
            String found = vehiclePrimaryIds.get(registration);
            if (found != null && Collections.frequency(new ArrayList<>(vehiclePrimaryIds.values()), found) == 1) return found;
            if (found != null) vehiclePrimaryIds.remove(registration);
            Set<String> used = new HashSet<>(vehiclePrimaryIds.values());
            String uid;
            do { uid = String.format(Locale.ROOT, "VH%06d", vehicleIdCounter++); } while (used.contains(uid));
            vehiclePrimaryIds.put(registration, uid);
            return uid;
        }
        synchronized int estimatedCost(String registration, int start, int end) {
            Vehicle v = vehicles.get(registration);
            int ratePerHour = (v != null && "TWO_WHEELER".equalsIgnoreCase(v.getVehicleType())) ? 10 : 30;
            int hours = Math.max(1, (int)Math.ceil((end - start) / 60.0));
            return hours * ratePerHour;
        }
        synchronized int visitCount(String registration) { return vehicleVisitCounts.getOrDefault(registration,0); }
        synchronized List<Vehicle> vehiclesForUser(String mobile) { List<Vehicle> result=new ArrayList<>(); for(Map.Entry<String,String> e:vehicleOwners.entrySet()) if(Objects.equals(mobile,e.getValue())) { Vehicle v=vehicles.get(e.getKey()); if(v!=null) result.add(v); } return result; }
        synchronized void registerOwnedVehicle(String mobile, String id, String type, boolean permitValid) {
            if (!users.containsKey(mobile)) throw new IllegalArgumentException("Please sign in again before adding a vehicle.");
            if (vehicleOwners.containsKey(id)) throw new IllegalArgumentException("That registration number is already linked to an account.");
            registerVehicle(id,type,permitValid); vehicleOwners.put(id,mobile); primaryIdFor(id);
        }

        synchronized void registerUser(String name, String mobile, String vehicleId, String type, boolean permitValid, String passwordHash) {
            if (name.isBlank()) throw new IllegalArgumentException("Enter your full name.");
            if (users.containsKey(mobile)) throw new IllegalArgumentException("This mobile number is already registered. Please sign in.");
            if (!Set.of("CAR", "TWO_WHEELER", "ACCESSIBLE").contains(type)) throw new IllegalArgumentException("Select a valid vehicle type.");
            if ("ACCESSIBLE".equals(type) && !permitValid) throw new IllegalArgumentException("A valid accessible parking permit is required for accessible parking.");
            Vehicle existingVehicle = vehicles.get(vehicleId);
            if (existingVehicle != null && vehicleOwners.containsKey(vehicleId)) throw new IllegalArgumentException("That vehicle registration number is already linked to an account.");
            if (existingVehicle != null && !existingVehicle.getVehicleType().equalsIgnoreCase(type)) throw new IllegalArgumentException("This vehicle is already registered with a different vehicle type.");
            if (existingVehicle != null && existingVehicle.isPermitRequired() && !existingVehicle.isPermitValid()) throw new IllegalArgumentException("This existing vehicle has no valid accessible permit. Ask the administrator to update its record.");
            users.put(mobile, new UserAccount(name, mobile, passwordHash));
            if (existingVehicle == null) registerVehicle(vehicleId, type, permitValid);
            vehicleOwners.put(vehicleId, mobile);
            primaryIdFor(vehicleId);
            history.record(currentTime, "USER_REGISTERED", "User registered: " + name + " (" + mobile + ") with vehicle " + vehicleId);
        }

        synchronized boolean isOwnedVehicle(String vehicleId, String mobile) {
            return mobile != null && mobile.equals(vehicleOwners.get(vehicleId));
        }
        synchronized Vehicle vehicleForUser(String mobile) {
            for (Map.Entry<String, String> e : vehicleOwners.entrySet()) if (Objects.equals(mobile, e.getValue())) return vehicles.get(e.getKey());
            return null;
        }
        synchronized String firstVehicleIdForUser(String mobile) {
            for (Map.Entry<String, String> e : vehicleOwners.entrySet()) if (Objects.equals(mobile, e.getValue())) return e.getKey();
            return "";
        }
        synchronized List<Reservation> reservationsForUser(String mobile) {
            List<Reservation> result = new ArrayList<>();
            for (Reservation r : reservations) if (isOwnedVehicle(r.getVehicleId(), mobile)) result.add(r);
            result.sort(Comparator.comparingInt(Reservation::getStartTime));
            return result;
        }
        synchronized long activeReservationsForUserCount(String mobile) {
            return reservations.stream()
                    .filter(r -> isOwnedVehicle(r.getVehicleId(), mobile))
                    .filter(r -> r.isActive() || r.isCheckedIn())
                    .count();
        }
        synchronized Reservation findReservationById(String id) {
            for (Reservation r : reservations) if (r.getReservationId().equalsIgnoreCase(id)) return r;
            return null;
        }

        synchronized void registerVehicle(String id, String type, boolean permitValid) {
            if (vehicles.containsKey(id)) throw new IllegalArgumentException("That vehicle ID is already registered.");
            if (!Set.of("CAR", "TWO_WHEELER", "ACCESSIBLE").contains(type)) throw new IllegalArgumentException("Select a valid vehicle type.");
            boolean required = type.equals("ACCESSIBLE");
            if (required && !permitValid) throw new IllegalArgumentException("A valid accessible parking permit is required for accessible parking.");
            vehicles.put(id, new Vehicle(id, type, required, required && permitValid));
            primaryIdFor(id);
            history.record(currentTime, "ARRIVAL", "Vehicle " + id + " registered as " + type);
        }

        synchronized String parkVehicle(String id) {
            Vehicle v = vehicles.get(id);
            if (v == null) throw new IllegalArgumentException("Register this vehicle before parking.");
            if (parkedVehicles.containsKey(id)) throw new IllegalStateException("This vehicle is already parked in " + parkedVehicles.get(id).getSpaceId() + ".");
            Map<String, Integer> travel = roads.shortestTravelTimes("ENTRY");
            ParkingLot bestLot = null; ParkingSpace bestSpace = null; int bestTime = Integer.MAX_VALUE;
            for (ParkingLot lot : lots) {
                int travelTime = travel.getOrDefault(lot.getLotId(), RoadNetwork.UNREACHABLE);
                for (ParkingSpace space : lot.getSpaces()) {
                    // A walk-in parking session has no scheduled checkout, so do not use a space
                    // that has any active reservation later today.
                    boolean conflict = hasActiveReservationConflict(space.getSpaceId(), currentTime, 1440);
                    if (policy.isEligible(space, v, conflict) && travelTime < bestTime) {
                        bestLot = lot; bestSpace = space; bestTime = travelTime;
                    }
                }
            }
            if (bestSpace == null) throw new IllegalStateException("No compatible space is available. Waiting-list support can be added next.");
            bestSpace.occupy(); parkedVehicles.put(id, bestSpace); parkingStartTimes.put(id, currentTime);
            vehicleVisitCounts.merge(id, 1, Integer::sum);
            history.record(currentTime, "PARKED", "Vehicle " + id + " assigned to " + bestLot.getLotId() + "/" + bestSpace.getSpaceId() + " (travel " + bestTime + " min)");
            return "Vehicle " + id + " parked in " + bestLot.getLotId() + " / " + bestSpace.getSpaceId() + ". Estimated route travel time: " + bestTime + " minutes.";
        }

        synchronized String reserve(String vehicleId, int start, int end, String requestedLot, String sourceJunction, String requestedSpace, String requesterMobile) {
            Vehicle v = vehicles.get(vehicleId);
            if (v == null) throw new IllegalArgumentException("Register this vehicle before reserving.");
            if (end <= start) throw new IllegalArgumentException("End time must be later than start time.");
            if (start < 0 || start >= 1440 || end > 1440) throw new IllegalArgumentException("Reservation times must be within the same day (00:00–24:00).");
            if (start <= currentTime) throw new IllegalArgumentException("Choose a start time later than the current local time (" + timeText(currentTime) + ").");
            if (!junctions().contains(sourceJunction)) throw new IllegalArgumentException("Choose a valid road junction.");
            if (!requestedLot.equals("ANY") && lots.stream().noneMatch(l -> l.getLotId().equals(requestedLot))) throw new IllegalArgumentException("Choose a valid parking lot.");

            for (Reservation existing : reservations) {
                boolean sameVehicle = existing.getVehicleId().equalsIgnoreCase(vehicleId);
                boolean stillRelevant = existing.isActive() || existing.isCheckedIn();
                if (sameVehicle && stillRelevant && existing.overlaps(start, end)) {
                    throw new IllegalStateException("Vehicle " + vehicleId + " already has reservation " + existing.getReservationId()
                            + " from " + timeText(existing.getStartTime()) + " to " + timeText(existing.getEndTime())
                            + ". The same vehicle cannot have overlapping reservations, even in different lots.");
                }
            }

            ParkingSpace chosen = null;
            ParkingLot chosenLot = null;
            int chosenTravel = Integer.MAX_VALUE;
            Map<String, Integer> travel = roads.shortestTravelTimes(sourceJunction);
            boolean specificSpace = requestedSpace != null && !requestedSpace.isBlank() && !requestedSpace.equals("ANY");
            for (ParkingLot lot : lots) {
                if (!requestedLot.equals("ANY") && !lot.getLotId().equals(requestedLot)) continue;
                int route = travel.getOrDefault(lot.getLotId(), RoadNetwork.UNREACHABLE);
                if (route >= RoadNetwork.UNREACHABLE) continue;
                for (ParkingSpace space : lot.getSpaces()) {
                    if (specificSpace && !space.getSpaceId().equalsIgnoreCase(requestedSpace)) continue;
                    boolean conflict = hasActiveReservationConflict(space.getSpaceId(), start, end);
                    if (!space.isOccupied() && policy.isEligibleForReservation(space, v, conflict)
                            && (chosen == null || route < chosenTravel || (route == chosenTravel && space.getSpaceId().compareTo(chosen.getSpaceId()) < 0))) {
                        chosen = space;
                        chosenLot = lot;
                        chosenTravel = route;
                    }
                }
            }
            if (chosen == null) {
                if (specificSpace) throw new IllegalStateException("Space " + requestedSpace + " is not eligible for this vehicle or is occupied/reserved during that interval. Choose another space.");
                if (!requestedLot.equals("ANY")) throw new IllegalStateException("No compatible free space in " + requestedLot + " for that interval. Choose another lot or time.");
                throw new IllegalStateException("No compatible space is available for that interval in any lot. Try another time.");
            }
            for (Reservation existing : reservations) {
                if ((existing.isActive() || existing.isCheckedIn()) && existing.getSpaceId().equalsIgnoreCase(chosen.getSpaceId())
                        && existing.getEndTime() == start && !existing.getVehicleId().equalsIgnoreCase(vehicleId)) {
                    String holderMobile = vehicleOwners.get(existing.getVehicleId());
                    if (holderMobile != null && !holderMobile.equals(requesterMobile)) {
                        String pendingId = String.format("P%03d", pendingCounter++);
                        PendingExtension pending = new PendingExtension(pendingId, requesterMobile, vehicleId, chosen.getSpaceId(), chosenLot.getLotId(), sourceJunction, existing.getReservationId(), start, end, chosenTravel);
                        pendingExtensions.put(pendingId, pending);
                        notifyUser(holderMobile, "Extension decision needed: " + vehicleId + " requests " + chosen.getSpaceId() + " from " + timeText(start) + " to " + timeText(end) + ". Extend your reservation to " + timeText(end) + " or decline?", pendingId);
                        history.record(currentTime, "EXTENSION_REQUEST", "Pending request " + pendingId + " for " + chosen.getSpaceId() + " awaits " + existing.getVehicleId());
                        return "Request sent to the current reservation holder. Your booking is pending until they decide.";
                    }
                }
            }
            String id = String.format("R%03d", reservationCounter++);
            reservations.add(new Reservation(id, vehicleId, chosen.getSpaceId(), start, end));
            reservationDates.put(id, LocalDate.now());
            history.record(currentTime, "RESERVATION", "Reservation " + id + " for " + vehicleId + " at " + chosenLot.getLotId() + "/" + chosen.getSpaceId()
                    + " from " + timeText(start) + " to " + timeText(end) + " (travel " + chosenTravel + " min from " + sourceJunction + ")");
            return "Reservation " + id + " created for " + chosenLot.getLotId() + " / " + chosen.getSpaceId()
                    + ". Estimated travel time from " + sourceJunction.replace('-', ' ') + ": " + chosenTravel + " minutes.";
        }

        private void notifyUser(String mobile, String text, String pendingId) {
            if (mobile != null && !mobile.isBlank()) {
                notifications.add(new AppNotification(mobile, text, pendingId));
                history.record(currentTime, "NOTIFICATION", "To " + mobile + ": " + text);
            }
        }

        synchronized List<AppNotification> notificationsFor(String mobile) {
            List<AppNotification> result = new ArrayList<>();
            for (AppNotification n : notifications) if (Objects.equals(n.mobile, mobile)) result.add(n);
            return result;
        }

        synchronized void respondToExtension(String pendingId, String mobile, String decision) {
            PendingExtension pending = pendingExtensions.get(pendingId);
            if (pending == null) throw new IllegalArgumentException("This extension request is no longer pending.");
            Reservation current = findReservation(pending.currentReservationId);
            if (current == null || !Objects.equals(vehicleOwners.get(current.getVehicleId()), mobile)) throw new IllegalArgumentException("This request does not belong to your account.");
            if ("extend".equals(decision)) {
                for (Reservation other : reservations) {
                    if (other == current || !(other.isActive() || other.isCheckedIn())) continue;
                    if (other.getSpaceId().equalsIgnoreCase(current.getSpaceId()) && other.overlaps(current.getStartTime(), pending.end))
                        throw new IllegalStateException("This space has another booking before that extension time. Please decline the extension request.");
                }
                if (!current.extendTo(pending.end)) throw new IllegalStateException("This reservation cannot be extended.");
                reminderSent.remove(current.getReservationId());
                notifyUser(mobile, "Your reservation " + current.getReservationId() + " was extended until " + timeText(pending.end) + ".", null);
                notifyUser(pending.requesterMobile, "Your request for " + pending.spaceId + " was not confirmed because the current holder extended their stay. Choose another space or time.", null);
                history.record(currentTime, "EXTENSION_ACCEPTED", current.getReservationId() + " extended until " + timeText(pending.end) + "; " + pending.id + " rejected");
            } else if ("decline".equals(decision)) {
                String id = String.format("R%03d", reservationCounter++);
                reservations.add(new Reservation(id, pending.requesterVehicle, pending.spaceId, pending.start, pending.end));
                reservationDates.put(id, LocalDate.now());
                notifyUser(mobile, "You declined the extension. The next reservation for " + pending.spaceId + " is scheduled from " + timeText(pending.start) + ".", null);
                notifyUser(pending.requesterMobile, "Reservation " + id + " confirmed for " + pending.spaceId + " from " + timeText(pending.start) + " to " + timeText(pending.end) + ".", null);
                history.record(currentTime, "RESERVATION", "Reservation " + id + " confirmed after extension request " + pending.id + " was declined");
            } else throw new IllegalArgumentException("Choose extend or decline.");
            pendingExtensions.remove(pendingId);
        }

        synchronized void confirmExit(String reservationId, String mobile) {
            Reservation r = findReservation(reservationId);
            if (r == null || !Objects.equals(vehicleOwners.get(r.getVehicleId()), mobile)) throw new IllegalArgumentException("Reservation not found for your account.");
            List<String> relatedPending = new ArrayList<>();
            for (PendingExtension pending : pendingExtensions.values()) if (pending.currentReservationId.equals(reservationId)) relatedPending.add(pending.id);
            for (String pendingId : relatedPending) respondToExtension(pendingId, mobile, "decline");
            history.record(currentTime, "EXIT_CONFIRMED", mobile + " confirmed exit for " + reservationId + " at " + timeText(r.getEndTime()));
            notifyUser(mobile, "Exit confirmed for reservation " + reservationId + " at " + timeText(r.getEndTime()) + ".", null);
        }

        synchronized void cancelReservation(String id) {
            Reservation r = findReservation(id);
            if (r == null || !r.cancel()) throw new IllegalStateException("Active reservation not found.");
            history.record(currentTime, "CANCELLATION", "Reservation " + id + " cancelled");
        }

        synchronized void checkIn(String id) {
            Reservation r = findReservation(id);
            if (r == null) throw new IllegalStateException("Reservation not found.");
            if (!r.isActive()) throw new IllegalStateException("This reservation is no longer active.");
            if (currentTime < r.getStartTime()) throw new IllegalStateException("Too early to check in. Check-in opens at " + timeText(r.getStartTime()) + ".");
            if (currentTime >= r.getEndTime()) throw new IllegalStateException("This reservation has ended. Please create a new reservation.");

            ParkingSpace space = findSpace(r.getSpaceId());
            if (space == null || space.isOccupied()) throw new IllegalStateException("Reserved space is currently occupied.");
            r.checkIn(); space.occupy(); parkedVehicles.put(r.getVehicleId(), space); parkingStartTimes.put(r.getVehicleId(), currentTime);
            vehicleVisitCounts.merge(r.getVehicleId(), 1, Integer::sum);
            history.record(currentTime, "CHECK_IN", "Vehicle " + r.getVehicleId() + " checked in to " + r.getSpaceId() + " for " + id);
        }

        synchronized void confirmDemoPayment(String receiptId, String mobile, String method) {
            PaymentRecord payment = payments.stream().filter(p -> p.id.equalsIgnoreCase(receiptId)).findFirst().orElse(null);
            if (payment == null) throw new IllegalArgumentException("Payment record was not found.");
            if (!Objects.equals(payment.ownerMobile, mobile)) throw new IllegalArgumentException("This payment does not belong to your account.");
            if ("PAID".equalsIgnoreCase(payment.status)) throw new IllegalStateException("This receipt has already been paid.");
            payment.status = "PAID";
            payment.method = method;
            history.record(currentTime, "DEMO_PAYMENT", "Demo payment " + payment.id + " confirmed by " + mobile + " using " + method + "; amount ₹" + payment.amount);
        }

        synchronized String checkOut(String vehicleId) {
            ParkingSpace space = parkedVehicles.remove(vehicleId);
            if (space == null) throw new IllegalStateException("This vehicle is not currently parked.");
            int start = parkingStartTimes.getOrDefault(vehicleId, currentTime);
            int durationMinutes = Math.max(1, currentTime - start);
            int hours = Math.max(1, (int)Math.ceil(durationMinutes / 60.0));
            Vehicle vehicle = vehicles.get(vehicleId);
            int rate = vehicle != null && "TWO_WHEELER".equalsIgnoreCase(vehicle.getVehicleType()) ? 10 : 30;
            int amount = hours * rate;
            parkingStartTimes.remove(vehicleId);
            space.release();
            for (Reservation r : reservations) if (r.getVehicleId().equalsIgnoreCase(vehicleId) && r.isCheckedIn()) { r.checkOut(); break; }
            String receiptId = String.format(Locale.ROOT, "P%06d", receiptCounter++);
            String ownerMobile = vehicleOwners.getOrDefault(vehicleId, "");
            PaymentRecord payment = new PaymentRecord(receiptId, vehicleId, ownerMobile, space.getSpaceId(), LocalDate.now().toString(), start, currentTime, durationMinutes, rate, amount);
            payments.add(payment);
            history.record(currentTime, "CHECK_OUT", "Vehicle " + vehicleId + " checked out from " + space.getSpaceId() + "; duration " + durationMinutes + " minutes; charge ₹" + amount + "; receipt " + receiptId);
            return "Vehicle " + vehicleId + " checked out from " + space.getSpaceId() + ". Parking duration: " + durationMinutes + " minutes. Charge: ₹" + amount + " (" + hours + " billed hour(s) at ₹" + rate + "/hour). Receipt: " + receiptId + ". Open My parking history to view or print it.";
        }

        /** Synchronize reservation processing with the computer's real local date and time. */
        synchronized boolean tickClock() {
            LocalDate today = LocalDate.now();
            int now = LocalTime.now().getHour() * 60 + LocalTime.now().getMinute();
            boolean changed = false;

            if (!today.equals(currentDate)) {
                currentDate = today;
                reminderSent.clear();
                changed = true;
            }

            if (now != currentTime) currentTime = now;
            for (Reservation r : reservations) {
                LocalDate bookingDate = reservationDates.getOrDefault(r.getReservationId(), today);
                // Expire by the actual booking date and time, never by clock time alone.
                if (bookingDate.isBefore(today) && r.isActive()) {
                    r.expire(); history.record(now, "EXPIRED", "Reservation " + r.getReservationId() + " expired after its booking date"); changed = true;
                } else if (bookingDate.isBefore(today) && r.isCheckedIn()) {
                    r.restoreStatus("COMPLETED"); changed = true;
                }
                if (!bookingDate.equals(today)) continue;
                if ((r.isActive() || r.isCheckedIn()) && now >= r.getEndTime() - 15 && now < r.getEndTime() && reminderSent.add(r.getReservationId())) {
                    String mobile = vehicleOwners.get(r.getVehicleId());
                    notifyUser(mobile, "Reminder: reservation " + r.getReservationId() + " for " + r.getSpaceId() + " ends at " + timeText(r.getEndTime()) + ". Confirm your exit or respond to an extension request if one is pending.", null);
                    changed = true;
                }
                if (bookingDate.equals(today) && r.isActive() && r.isExpired(now)) {
                    r.expire(); history.record(now, "EXPIRED", "Reservation " + r.getReservationId() + " expired"); changed = true;
                }
            }
            return changed;
        }

        private Reservation findReservation(String id) {
            for (Reservation r : reservations) if (r.getReservationId().equalsIgnoreCase(id)) return r;
            return null;
        }
        private ParkingSpace findSpace(String id) {
            for (ParkingLot lot : lots) { ParkingSpace s = lot.findSpace(id); if (s != null) return s; }
            return null;
        }
        private boolean hasActiveReservationConflict(String spaceId, int start, int end) {
            for (Reservation r : reservations) if (reservationDates.getOrDefault(r.getReservationId(), currentDate).equals(LocalDate.now()) && r.getSpaceId().equalsIgnoreCase(spaceId) && r.isActive() && r.overlaps(start, end)) return true;
            return false;
        }
        synchronized String adminSpaceTooltip(ParkingSpace space) {
            if (space == null) return "Parking space details unavailable";
            StringBuilder info = new StringBuilder();
            info.append("Space: ").append(space.getSpaceId()).append("\nLot: ").append(lotForSpace(space.getSpaceId()));
            String vehicleId = null;
            for (Map.Entry<String, ParkingSpace> entry : parkedVehicles.entrySet()) {
                if (entry.getValue().getSpaceId().equalsIgnoreCase(space.getSpaceId())) { vehicleId = entry.getKey(); break; }
            }
            Reservation activeReservation = null;
            for (Reservation r : reservations) {
                if (r.getSpaceId().equalsIgnoreCase(space.getSpaceId()) && r.isActive()
                        && reservationDates.getOrDefault(r.getReservationId(), currentDate).equals(LocalDate.now())) {
                    activeReservation = r; break;
                }
            }
            if (vehicleId != null) {
                String ownerMobile = vehicleOwners.getOrDefault(vehicleId, "Not linked to a user");
                UserAccount account = users.get(ownerMobile);
                info.append("\nStatus: OCCUPIED\nVehicle: ").append(vehicleId);
                info.append("\nUser: ").append(account == null ? "Unknown" : account.name);
                info.append("\nMobile: ").append(ownerMobile);
            } else if (activeReservation != null) {
                String ownerMobile = vehicleOwners.getOrDefault(activeReservation.getVehicleId(), "Not linked to a user");
                UserAccount account = users.get(ownerMobile);
                info.append("\nStatus: RESERVED\nReservation: ").append(activeReservation.getReservationId());
                info.append("\nVehicle: ").append(activeReservation.getVehicleId());
                info.append("\nUser: ").append(account == null ? "Unknown" : account.name);
                info.append("\nMobile: ").append(ownerMobile);
                info.append("\nDate: ").append(reservationDates.getOrDefault(activeReservation.getReservationId(), currentDate));
                info.append("\nTime: ").append(timeText(activeReservation.getStartTime())).append("–").append(timeText(activeReservation.getEndTime()));
            } else {
                info.append("\nStatus: ").append(spaceStatus(space));
                info.append("\nNo current user assigned");
            }
            info.append("\nMove pointer away to close");
            return info.toString();
        }

        String spaceStatus(ParkingSpace s) {
            if (s == null) return "AVAILABLE";
            boolean upcoming = false;
            for (Reservation r : reservations) {
                if (!reservationDates.getOrDefault(r.getReservationId(), currentDate).equals(LocalDate.now()) || !r.isActive() || !r.getSpaceId().equalsIgnoreCase(s.getSpaceId())) continue;
                if (r.getStartTime() <= currentTime && currentTime < r.getEndTime()) {
                    return s.isOccupied() ? "CONFLICT" : "RESERVED";
                }
                if (r.getStartTime() > currentTime) upcoming = true;
            }
            if (s.isOccupied()) return upcoming ? "CONFLICT" : "OCCUPIED";
            return upcoming ? "UPCOMING" : "AVAILABLE";
        }
        ParkingSpace findSpaceForDashboard(String id) { return findSpace(id); }
        List<Reservation> upcomingReservations() {
            List<Reservation> result = new ArrayList<>();
            for (Reservation r : reservations) if (reservationDates.getOrDefault(r.getReservationId(), currentDate).equals(LocalDate.now()) && r.isActive() && r.getStartTime() > currentTime) result.add(r);
            result.sort(Comparator.comparingInt(Reservation::getStartTime).thenComparing(Reservation::getReservationId));
            return result;
        }
        int upcomingReservationCount() { return upcomingReservations().size(); }
        List<String> junctions() { return List.of("ENTRY", "WEST-GATE", "JUNCTION-1", "JUNCTION-2", "EAST-GATE"); }
        Map<String, Integer> travelTimesFrom(String junction) { return roads.shortestTravelTimes(junction); }
        String reservationDateText(Reservation r) { return reservationDates.getOrDefault(r.getReservationId(), currentDate).toString(); }
        String lotForSpace(String spaceId) {
            for (ParkingLot lot : lots) if (lot.findSpace(spaceId) != null) return lot.getLotId();
            return "Unknown lot";
        }
        int reservedNowForLot(String lotId) {
            int count = 0;
            for (Reservation r : reservations) {
                if (!reservationDates.getOrDefault(r.getReservationId(), currentDate).equals(LocalDate.now()) || !r.isActive() || currentTime < r.getStartTime() || currentTime >= r.getEndTime()) continue;
                if (Objects.equals(lotForSpace(r.getSpaceId()), lotId)) {
                    ParkingSpace space = findSpace(r.getSpaceId());
                    if (space != null && !space.isOccupied()) count++;
                }
            }
            return count;
        }
        int reservedLaterForLot(String lotId) {
            Set<String> reservedSpaces = new HashSet<>();
            for (Reservation r : reservations) {
                if (!reservationDates.getOrDefault(r.getReservationId(), currentDate).equals(LocalDate.now()) || !r.isActive() || r.getStartTime() <= currentTime) continue;
                if (!Objects.equals(lotForSpace(r.getSpaceId()), lotId)) continue;
                ParkingSpace space = findSpace(r.getSpaceId());
                if (space != null && !space.isOccupied() && !spaceStatus(space).equals("RESERVED")) reservedSpaces.add(space.getSpaceId());
            }
            return reservedSpaces.size();
        }

        int totalSpaces() { return lots.stream().mapToInt(ParkingLot::getTotalSpaces).sum(); }
        int occupiedSpaces() { return (int) lots.stream().flatMap(l -> l.getSpaces().stream()).filter(ParkingSpace::isOccupied).count(); }
        int reservedNowSpaces() { return (int) lots.stream().flatMap(l -> l.getSpaces().stream()).filter(s -> !s.isOccupied() && spaceStatus(s).equals("RESERVED")).count(); }
        synchronized int availableSpaceCountForType(String type) {
            int count = 0;
            for (ParkingLot lot : lots) for (ParkingSpace space : lot.getSpaces()) {
                if (space.isOccupied()) continue;
                String spaceType = space.getSpaceType();
                boolean matching = "ALL".equals(type)
                    || ("CAR".equals(type) && "CAR".equalsIgnoreCase(spaceType))
                    || ("TWO_WHEELER".equals(type) && "TWO_WHEELER".equalsIgnoreCase(spaceType))
                    || ("ACCESSIBLE".equals(type) && "ACCESSIBLE".equalsIgnoreCase(spaceType));
                if (!matching) continue;
                String status = spaceStatus(space);
                if (!"RESERVED".equals(status)) count++;
            }
            return count;
        }
        int availableNowSpaces() { return availableSpaceCountForType("ALL"); }
        List<ParkingSpace> eligibleSpacesForReservation(String vehicleId, int start, int end, String requestedLot, String sourceJunction) {
            Vehicle vehicle = vehicles.get(vehicleId);
            if (vehicle == null || end <= start || !junctions().contains(sourceJunction)) return Collections.emptyList();
            Map<String, Integer> travel = roads.shortestTravelTimes(sourceJunction);
            List<ParkingSpace> result = new ArrayList<>();
            for (ParkingLot lot : lots) {
                if (!requestedLot.equals("ANY") && !lot.getLotId().equals(requestedLot)) continue;
                if (travel.getOrDefault(lot.getLotId(), RoadNetwork.UNREACHABLE) >= RoadNetwork.UNREACHABLE) continue;
                for (ParkingSpace space : lot.getSpaces()) {
                    boolean conflict = hasActiveReservationConflict(space.getSpaceId(), start, end);
                    if (!space.isOccupied() && policy.isEligibleForReservation(space, vehicle, conflict)) result.add(space);
                }
            }
            result.sort(Comparator.comparingInt((ParkingSpace space) -> travel.getOrDefault(lotForSpace(space.getSpaceId()), RoadNetwork.UNREACHABLE)).thenComparing(ParkingSpace::getSpaceId));
            return result;
        }
        int getCurrentTime() { return currentTime; }
        String timeText(int minutes) { return String.format("%02d:%02d", minutes / 60, minutes % 60); }
    }
}
