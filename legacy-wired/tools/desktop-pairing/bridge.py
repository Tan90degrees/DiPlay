"""Private stdio transport to localhost Apple USB service; never print protocol bytes."""
import socket
import struct
import sys

def exact(stream, count):
    out = bytearray()
    while len(out) < count:
        data = stream.read(count - len(out))
        if not data:
            raise EOFError
        out.extend(data)
    return bytes(out)

def response(status, data=b''):
    sys.stdout.buffer.write(struct.pack('>BI', status, len(data)) + data)
    sys.stdout.buffer.flush()

def main():
    connection = None
    try:
        connection = socket.create_connection(('127.0.0.1', 27015), timeout=5)
        response(0)
        while True:
            operation = exact(sys.stdin.buffer, 1)[0]
            if operation == 1:
                count = struct.unpack('>I', exact(sys.stdin.buffer, 4))[0]
                if not 0 <= count <= 1_048_576:
                    raise ValueError('Invalid bridge write length')
                connection.settimeout(5)
                connection.sendall(exact(sys.stdin.buffer, count))
                response(0)
            elif operation == 2:
                count, timeout = struct.unpack('>II', exact(sys.stdin.buffer, 8))
                if not 1 <= count <= 1_048_576 or not 1 <= timeout <= 300_000:
                    raise ValueError('Invalid bridge read bounds')
                connection.settimeout(timeout / 1000)
                try:
                    response(0, connection.recv(count))
                except socket.timeout:
                    response(1)
            elif operation == 3:
                return
            else:
                raise ValueError('Unknown bridge operation')
    except EOFError:
        pass
    except Exception as error:
        response(2, (type(error).__name__ + ': ' + str(error)).encode()[:300])
    finally:
        if connection is not None:
            connection.close()

if __name__ == '__main__':
    main()
