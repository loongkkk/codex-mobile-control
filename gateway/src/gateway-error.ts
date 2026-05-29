import type { GatewayErrorResponse } from "../../shared/src/api";

export class GatewayHttpError extends Error {
  constructor(
    readonly statusCode: number,
    readonly body: GatewayErrorResponse
  ) {
    super(body.message);
    this.name = "GatewayHttpError";
  }
}

export function isGatewayHttpError(error: unknown): error is GatewayHttpError {
  return error instanceof GatewayHttpError;
}
