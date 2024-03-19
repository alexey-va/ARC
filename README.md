# ARC Minecraft Plugin

## Description
ARC is a comprehensive Minecraft plugin that provides a variety of features to enhance gameplay. It is a monolith core plugin for my personal server. It includes a treasure hunt system, building management, stock market simulation, auction system, and more.

## Features
- **Treasure Hunt System**: Players can participate in treasure hunts with configurable rewards.
- **Building Management**: Allows players to construct and manage buildings built by NPCs.
- **Stock Market Simulation**: Players can invest in a simulated stock market.
- **Auction System**: Adds redis pubsub system for auction items which can be used by external discord bot.
- **X-Server**: Adds various cross server capabilities utilizing redis pubSub.
- **Incompatibility fixes** for various plugins present on my server.
- **Announcement Board** for x-server announcements by players.
- **Farms/Mines** as an activity for players. It uses randomized approach to ore placement to distinguish it from similar plugins.

## Installation
1. Clone the repository: `git clone https://github.com/alexey-va/ARC.git`
2. Build the project using Maven: `mvn clean install`
3. Place the generated .jar file into your Minecraft server's `plugins` directory.
4. Restart your Minecraft server.

## Configuration
The plugin's behavior can be customized through various configuration files located in the `src/main/resources` directory.

## Dependencies
- **Vault**: Used for economy features.
- **Redis**: Used for data storage and caching.

## Logging
ARC uses log4j2 for logging. The configuration can be found in `src/main/resources/log4j2.properties`.

## Contributing
Contributions are welcome! Please fork the repository and create a pull request with your changes.

## License
This project is licensed under the [MIT License](LICENSE).

## Contact
For any issues or suggestions, please open an issue on GitHub.