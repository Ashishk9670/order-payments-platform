import { execFile, execFileSync } from 'node:child_process';
import { promisify } from 'node:util';

/**
 * Failure injection against the docker compose stack (project name "order-platform", see docker-compose.yml). Containers are
 * paused (SIGSTOP: the process is alive but answers nothing, which is how a hung dependency looks) or stopped and started.
 * Everything is undone in a finally block, so a failing test cannot leave the stack broken for the next one.
 */
export const containerOf = (service: string) => `order-platform-${service}-1`;

export function docker(...args: string[]): string {
  return execFileSync('docker', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
}

export async function whilePaused<T>(service: string, during: () => Promise<T>): Promise<T> {
  docker('pause', containerOf(service));
  try {
    return await during();
  } finally {
    docker('unpause', containerOf(service));
  }
}

export async function whileStopped<T>(service: string, during: () => Promise<T>): Promise<T> {
  docker('stop', '--time', '1', containerOf(service));
  try {
    return await during();
  } finally {
    docker('start', containerOf(service));
  }
}

/** Async on purpose: a blocking restart would freeze the test's own event loop and drop the requests it is meant to overlap with. */
export const restart = async (service: string): Promise<void> => {
  await promisify(execFile)('docker', ['restart', '--time', '1', containerOf(service)]);
};

export const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));
