// Separate fixture dates keep each engine/viewport's rows isolated in the shared demo database.
const engines = ["chromium", "firefox", "webkit"];
const viewports = [
  { name: "desktop", viewport: { width: 1440, height: 1000 } },
  { name: "narrow", viewport: { width: 390, height: 844 } },
];
export const projects = engines.flatMap((browserName, engine) =>
  viewports.map(({ name, viewport }, width) => ({
    name: `${browserName}-${name}`,
    metadata: { fixtureDay: 15 + engine * viewports.length + width },
    use: { browserName, viewport },
  })),
);
export const fixtureDate = (info, year, month) =>
  `${year}-${month}-${info.project.metadata.fixtureDay}`;
