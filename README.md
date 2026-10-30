
# Smart Parking Allocation System — Java 17 Web Application

This version keeps the original SmartPark layout and adds account registration, role-based visibility, private user reservations, and in-app notifications. It uses Java 17, the built-in HTTP server, SQLite, and the SQLite JDBC driver.

## Run in IntelliJ IDEA (Windows / PowerShell)

1. Open the inner `SmartParkingWeb` folder (the folder containing `pom.xml` and `src`).
2. Place `sqlite-jdbc-3.53.4.0.jar` beside `pom.xml`.
3. Open IntelliJ Terminal in that same folder and run each command:

   ```powershell
   New-Item -ItemType Directory -Force out | Out-Null
   $files = Get-ChildItem .\src -Recurse -Filter *.java | ForEach-Object { $_.FullName }
   javac -encoding UTF-8 --add-modules jdk.httpserver -d out $files
   java --add-modules jdk.httpserver -cp "out;sqlite-jdbc-3.53.4.0.jar" SmartParkingWeb
   ```

4. Open `http://localhost:8081` in your browser. Stop the server with `Ctrl+C` in the terminal running it.

If Maven is installed, you can alternatively run `mvn clean compile exec:java` from the project folder.

## Accounts and access

- New users choose **Register**, then enter their name, mobile number, password, vehicle registration, and vehicle type. After signing in, users can open **My vehicles** to add more vehicles to the same account. Each vehicle receives a permanent unique ID such as `VH000001`; the registration number remains the vehicle plate, not the internal primary key.
- SQLite stores the vehicle primary key in `vehicle_registry.vehicle_uid` (PRIMARY KEY), with the registration number uniquely linked to it. Existing registered vehicles are assigned IDs automatically when the app starts.
- Administrators have **Search vehicle** to search by vehicle ID, plate, type, or owner mobile, and **Most frequent** for a separate ranked bar chart of vehicle visits. Counts increase on successful walk-in parking or reservation check-in and persist in SQLite. The chart reflects recorded visits since this feature was added; it does not reconstruct historical visits from older records.
- User accounts see their own vehicle and reservations, all parking-space availability, and their own notifications. They cannot access the administrator's vehicle list, event history, waiting queue, or time controls.
- Administrators use the same visual interface and can see all vehicles, reservations, waiting-list entries, and event history.
- Demo administrator credentials: username `admin`, password `admin123`. Change these credentials in `SmartParkingWeb.java` before any real deployment.
- Mobile number validation is local; no SMS OTP is sent. User passwords are hashed for this demo, but this app is not production security software.

## Parking rules and notifications

- Cars and two-wheelers do not need an accessibility permit. Accessible vehicles must confirm a valid accessible parking permit and are allocated only to compatible accessible spaces.
- Reservations use start/end times and prevent overlapping bookings for the same vehicle or space.
- The nearest eligible space is selected using Dijkstra's shortest-path travel time from the selected junction.
- The server reads your computer’s local clock and checks reservations automatically in the background every 10 seconds. Fifteen minutes before a reservation ends, the holder receives an in-app reminder without any manual time advancement.
- If another user requests the same space starting exactly when the current reservation ends, the current holder receives an in-app extension decision. Extending pushes the end time out and declines the waiting request; declining confirms the waiting user's reservation.
- Notifications are in-app only; no real SMS or push service is connected. The application must remain running for background checks to occur. Pending extension requests and notification cards are held in memory and reset when the server restarts. Refresh the page to see newly generated notifications.

## Database

`smartparking.db` is created in the working directory. It stores users, vehicles, vehicle ownership, unique vehicle primary IDs, vehicle visit counts, reservations, parked vehicles, the last processed calendar date, and event history. Reservation times are interpreted using the computer’s local clock and same-day bookings; at midnight, unfinished reservations from the previous day are closed automatically. Back up this file before replacing or deleting it.




